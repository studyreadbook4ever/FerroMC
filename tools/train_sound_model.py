#!/usr/bin/env python3
"""Train the 10,432-parameter scratch sound model and export fixed U8 QDQ ONNX."""
import argparse
import hashlib
import json
import math
import time
from pathlib import Path
import numpy as np
import torch
from torch import nn
from sound_model_common import BIN_LOW, BIN_HIGH, MIX, STRENGTH, make_dataset

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT/'artifacts'
ASSETS = ROOT/'app/src/main/assets'


class SoundModel(nn.Module):
    def __init__(self):
        super().__init__()
        self.layers = nn.Sequential(nn.Linear(32,64),nn.ReLU(),nn.Linear(64,64),nn.ReLU(),nn.Linear(64,64))
    def forward(self, x):
        return self.layers(x)


def probability_metrics(probabilities, target):
    entropy = -(target*np.log(target.clip(1e-12))).sum(-1).mean()
    ce = -(target*np.log(probabilities.clip(1e-12))).sum(-1).mean()
    return {'cross_entropy_nats':float(ce),'target_entropy_nats':float(entropy),
            'kl_to_target_nats':float(ce-entropy),'uniform_kl_nats':float(math.log(4)-entropy),
            'kl_reduction_vs_uniform_fraction':float(1-(ce-entropy)/(math.log(4)-entropy))}


@torch.no_grad()
def evaluate(model,data):
    probabilities=[]
    for start in range(0,len(data['input']),1024):
        logits=model(torch.from_numpy(data['input'][start:start+1024])).reshape(-1,4,4,4)
        selected=logits[torch.arange(len(logits)),torch.from_numpy(data['missing'][start:start+1024])]
        probabilities.append(selected.softmax(-1).numpy())
    return probability_metrics(np.concatenate(probabilities),data['targets'])


def export(model,path):
    import onnx
    from onnx import helper,TensorProto,numpy_helper
    nodes,initializers,previous=[],[],'sound_context'
    for i,index in enumerate((0,2,4)):
        layer=model.layers[index]
        w,b=f'linear{i}_weight',f'linear{i}_bias'
        initializers += [numpy_helper.from_array(layer.weight.detach().numpy(),w),numpy_helper.from_array(layer.bias.detach().numpy(),b)]
        output='sound_logits' if i==2 else f'linear{i}_output'
        nodes.append(helper.make_node('Gemm',[previous,w,b],[output],name=f'linear{i}',transB=1))
        if i<2:
            previous=f'relu{i}_output';nodes.append(helper.make_node('Relu',[output],[previous],name=f'relu{i}'))
    graph=helper.make_graph(nodes,'ferromc_masked_sound',
        [helper.make_tensor_value_info('sound_context',TensorProto.FLOAT,[16,32])],
        [helper.make_tensor_value_info('sound_logits',TensorProto.FLOAT,[16,64])],initializers)
    output=helper.make_model(graph,opset_imports=[helper.make_opsetid('',17)],producer_name='ferromc')
    output.ir_version=9;output=onnx.shape_inference.infer_shapes(output)
    onnx.checker.check_model(output);onnx.save(output,path)


def quantize(float_path,output_path,data):
    from onnxruntime.quantization import CalibrationDataReader,QuantFormat,QuantType,quantize_static
    class Calibration(CalibrationDataReader):
        def __init__(self):self.index=0
        def get_next(self):
            if self.index>=2048:return None
            result={'sound_context':data['input'][self.index:self.index+16]};self.index+=16
            return result
    quantize_static(str(float_path),str(output_path),Calibration(),quant_format=QuantFormat.QDQ,
        activation_type=QuantType.QUInt8,weight_type=QuantType.QUInt8,per_channel=False,
        op_types_to_quantize=['Gemm','Relu'],extra_options={'ActivationSymmetric':False,'WeightSymmetric':False,
        'DedicatedQDQPair':True,'QDQKeepRemovableActivations':True})


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--epochs',type=int,default=48)
    parser.add_argument('--train-contexts',type=int,default=32768)
    parser.add_argument('--validation-contexts',type=int,default=4096)
    parser.add_argument('--seed',type=int,default=20261022)
    args=parser.parse_args()
    torch.set_num_threads(4);torch.manual_seed(args.seed)
    started=time.time()
    train=make_dataset(args.seed,args.train_contexts,'train')
    validation=make_dataset(args.seed+1,args.validation_contexts,'validation')
    print(json.dumps({'dataset_seconds':time.time()-started}),flush=True)
    model=SoundModel();parameters=sum(p.numel() for p in model.parameters())
    assert parameters==10432 and parameters+82432<100000
    optimizer=torch.optim.AdamW(model.parameters(),lr=.003,weight_decay=.0001)
    schedule=torch.optim.lr_scheduler.CosineAnnealingLR(optimizer,args.epochs,eta_min=.00015)
    x,y,missing=[torch.from_numpy(train[k]) for k in ('input','targets','missing')]
    history=[{'epoch':0,**evaluate(model,validation)}];best=float('inf');state=None
    for epoch in range(1,args.epochs+1):
        model.train();permutation=torch.randperm(len(x))
        for offset in range(0,len(x),512):
            indices=permutation[offset:offset+512]
            logits=model(x[indices]).reshape(-1,4,4,4)[torch.arange(len(indices)),missing[indices]]
            loss=-(y[indices]*logits.log_softmax(-1)).sum(-1).mean()
            optimizer.zero_grad(set_to_none=True);loss.backward();optimizer.step()
        schedule.step();model.eval();metrics=evaluate(model,validation)
        history.append({'epoch':epoch,**metrics})
        if metrics['kl_to_target_nats']<best:
            best=metrics['kl_to_target_nats'];state={k:v.detach().clone() for k,v in model.state_dict().items()}
        if epoch%8==0:print(json.dumps(history[-1]),flush=True)
    model.load_state_dict(state);model.eval()
    torch.save({'state_dict':state,'args':vars(args)},ARTIFACTS/'sound_model_checkpoint.pt')
    export(model,ARTIFACTS/'sound_model_float.onnx')
    quantize(ARTIFACTS/'sound_model_float.onnx',ASSETS/'sound_model.onnx',validation)
    info={'model_version':'scratch-sound-v1','parameter_count':parameters,'combined_parameter_count':parameters+82432,
        'architecture':[32,64,64,64],'input_name':'sound_context','input_shape':[16,32],
        'output_name':'sound_logits','output_shape':[16,64],'dtype':'float32','internal_quantization':'QDQ uint8 activations and weights',
        'parameter_order':['tone','decay','drive','velocity'],'bin_low':BIN_LOW.tolist(),'bin_high':BIN_HIGH.tolist(),
        'input_voice_slots':['(pitch-36)/63','presence','tone/100','decay/100','drive/100','velocity/127','missing','reserved_zero'],
        'mask_contract':'Keep active pitch/presence; zero four parameters and set missing=1. Inactive voices are all zero.',
        'guidance':{'uniform_fraction':1-MIX,'roughness_fraction':MIX,'strength':STRENGTH},
        'limitations':['Synthetic approximate spectral roughness only; not pleasantness or exact Ferro DSP.',
            'The level-sensitive proxy can improve by lowering velocity or shortening decay; this is not evidence of harmonic consonance.',
            'No pretrained weights, music corpus, tonal rules, or phone training.',
            'Pitch model retains its original three-partial proxy; only this new model uses the Ferro Saw approximation.'],
        'sha256':hashlib.sha256((ASSETS/'sound_model.onnx').read_bytes()).hexdigest()}
    (ASSETS/'sound_model_info.json').write_text(json.dumps(info,indent=2)+'\n')
    report={'parameters':parameters,'combined_parameters':parameters+82432,'args':vars(args),'seconds':time.time()-started,
        'selected_validation':evaluate(model,validation),'training_history':history,'model':info,
        'split':'Disjoint hashed active pitch sets: train buckets0..7, validation8, independent test9.'}
    (ARTIFACTS/'sound_model_training_report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'seconds':report['seconds'],'validation':report['selected_validation'],'sha256':info['sha256']}),flush=True)


if __name__=='__main__':main()
