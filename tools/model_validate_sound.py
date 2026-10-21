#!/usr/bin/env python3
"""Independent sound-model holdout, quantization and app-sampler validation.

Desktop numerical checks only. This does not establish Android HTP execution,
exact Ferro sound equivalence, physical audibility, or musical pleasantness.
"""
import hashlib
import json
import math
import numpy as np
import onnx
import onnxruntime as ort
import torch
from train_sound_model import ARTIFACTS,ASSETS,SoundModel,evaluate,probability_metrics
from sound_model_common import BIN_LOW,BIN_HIGH,make_dataset,chord_scores
from model_validate_app_sampler import JavaRandom


def generate(session,seed,pitches,exploration=.55):
    rng=JavaRandom(seed);count=len(pitches)
    candidates=np.zeros((16,count,4),dtype=np.int64)
    for b in range(16):
        for voice in range(count):
            for parameter in range(4):
                candidates[b,voice,parameter]=16+rng.next_int(112) if parameter==3 else rng.next_int(101)
    temperature=.75+.9*exploration
    for missing in range(count):
        context=np.zeros((16,4,8),dtype=np.float32)
        for b in range(16):
            for voice in range(count):
                context[b,voice,0]=(int(pitches[voice])-36)/63
                context[b,voice,1]=1
                if voice==missing:context[b,voice,6]=1
                else:context[b,voice,2:6]=candidates[b,voice]/np.array([100,100,100,127])
        if session is None:logits=np.zeros((16,4,4,4),dtype=np.float32)
        else:logits=session.run(['sound_logits'],{'sound_context':context.reshape(16,32)})[0].reshape(16,4,4,4)
        if not np.isfinite(logits).all():raise ValueError('Nonfinite model logits')
        for b in range(16):
            for parameter in range(4):
                values=[float(v)/temperature for v in logits[b,missing,parameter]]
                maximum=max(values);weights=[math.exp(v-maximum)+.02*exploration for v in values]
                draw=rng.next_double()*sum(weights);selected=3
                for bin_index,weight in enumerate(weights):
                    draw-=weight
                    if draw<=0:selected=bin_index;break
                low=int(BIN_LOW[parameter,selected]);high=int(BIN_HIGH[parameter,selected])
                candidates[b,missing,parameter]=low+rng.next_int(high-low+1)
    return candidates[rng.next_int(16)].copy()


def diversity(parameters):
    output={}
    for p,name in enumerate(('tone','decay','drive','velocity')):
        values=parameters[...,p].ravel();counts=np.array([np.sum((values>=BIN_LOW[p,b])&(values<=BIN_HIGH[p,b])) for b in range(4)])
        probabilities=counts/counts.sum()
        output[name]={'minimum':int(values.min()),'maximum':int(values.max()),'distinct_values':int(len(np.unique(values))),
            'bin_counts':counts.tolist(),'normalized_bin_entropy':float(-(probabilities*np.log(probabilities.clip(1e-12))).sum()/math.log(4))}
    return output


def main():
    torch.set_num_threads(4)
    checkpoint=torch.load(ARTIFACTS/'sound_model_checkpoint.pt',map_location='cpu',weights_only=True)
    model=SoundModel();model.load_state_dict(checkpoint['state_dict']);model.eval()
    dataset=make_dataset(checkpoint['args']['seed']+19,4096,'test')
    floating=evaluate(model,dataset)
    options=ort.SessionOptions();options.intra_op_num_threads=1;options.inter_op_num_threads=1
    model_path=ASSETS/'sound_model.onnx';graph=onnx.load(model_path);onnx.checker.check_model(graph)
    sessions=[ort.InferenceSession(str(path),options,providers=['CPUExecutionProvider']) for path in (ARTIFACTS/'sound_model_float.onnx',model_path)]
    session=sessions[1]
    assert session.get_inputs()[0].name=='sound_context' and session.get_inputs()[0].shape==[16,32]
    assert session.get_outputs()[0].name=='sound_logits' and session.get_outputs()[0].shape==[16,64]
    assert session.get_inputs()[0].type==session.get_outputs()[0].type=='tensor(float)'
    probabilities=[[],[]];maximum_error=0
    for start in range(0,len(dataset['input']),16):
        outputs=[s.run(None,{'sound_context':dataset['input'][start:start+16]})[0] for s in sessions]
        maximum_error=max(maximum_error,float(np.abs(outputs[0]-outputs[1]).max()))
        for index,output in enumerate(outputs):
            assert np.isfinite(output).all()
            chosen=output.reshape(16,4,4,4)[np.arange(16),dataset['missing'][start:start+16]]
            probability=np.exp(chosen-chosen.max(-1,keepdims=True));probability/=probability.sum(-1,keepdims=True)
            probabilities[index].append(probability)
    probabilities=[np.concatenate(v) for v in probabilities]
    quantized=probability_metrics(probabilities[1],dataset['targets'])
    per_parameter={name:probability_metrics(probabilities[1][:,p:p+1],dataset['targets'][:,p:p+1])
                   for p,name in enumerate(('tone','decay','drive','velocity'))}
    probability_error=float(np.abs(probabilities[0]-probabilities[1]).mean())
    cases={};all_deltas=[];representative=[];passed=True
    pitch_rng=np.random.default_rng(20261111)
    for count in (2,3,4):
        pitches=np.sort(np.argsort(pitch_rng.random((512,36)),axis=1)[:,:count]+48,axis=1)
        neural=[];uniform=[]
        for i,chord in enumerate(pitches):
            seed=202610220000+i
            neural.append(generate(session,seed,chord));uniform.append(generate(None,seed,chord))
        neural=np.asarray(neural);uniform=np.asarray(uniform)
        learned_scores,control_scores=[chord_scores(pitches,p) for p in (neural,uniform)]
        delta=control_scores-learned_scores;all_deltas.append(delta)
        se=delta.std(ddof=1)/math.sqrt(len(delta));div=diversity(neural)
        changed=float(np.any(neural!=uniform,axis=(1,2)).mean())
        result={'samples':512,'pitch_range':[48,83],'exploration':.55,
            'neural_roughness_mean':float(learned_scores.mean()),'uniform_logit_control_roughness_mean':float(control_scores.mean()),
            'roughness_reduction_fraction':float(1-learned_scores.mean()/control_scores.mean()),
            'paired_improvement_95_percent_ci':[float(delta.mean()-1.96*se),float(delta.mean()+1.96*se)],
            'changed_results_vs_uniform_fraction':changed,'diversity':div}
        cases[str(count)]=result
        passed &= changed>.2 and all(v['normalized_bin_entropy']>.94 and all(n>0 for n in v['bin_counts']) for v in div.values())
        for i in range(4):
            representative.append({'count':count,'seed':202610220000+i,'pitches':pitches[i].tolist(),
                'tones':neural[i,:,0].tolist(),'decays':neural[i,:,1].tolist(),'drives':neural[i,:,2].tolist(),
                'velocities':neural[i,:,3].tolist(),'uniform_parameters':uniform[i].tolist()})
        print(json.dumps({'count':count,**result}),flush=True)
    delta=np.concatenate(all_deltas);se=delta.std(ddof=1)/math.sqrt(len(delta))
    interval=[float(delta.mean()-1.96*se),float(delta.mean()+1.96*se)]
    passed &= quantized['kl_reduction_vs_uniform_fraction']>.25 and probability_error<.02 and interval[0]>0
    info=json.loads((ASSETS/'sound_model_info.json').read_text())
    report={'checks_passed':bool(passed),'verification_scope':'Desktop ONNX Runtime CPU numerical validation only; Android HTP unverified here.',
        'test_seed':checkpoint['args']['seed']+19,'independent_test_contexts':4096,
        'sound_parameters':10432,'combined_pitch_and_sound_parameters':92864,
        'sound_model_sha256':hashlib.sha256(model_path.read_bytes()).hexdigest(),
        'pitch_model_sha256':hashlib.sha256((ASSETS/'seed_model.onnx').read_bytes()).hexdigest(),
        'float_model':floating,'quantized_model':quantized,'quantized_per_parameter':per_parameter,
        'quantization':{'maximum_logit_error':maximum_error,'mean_absolute_probability_error':probability_error},
        'operators':sorted({n.op_type for n in graph.graph.node}),'sampler_cases':cases,
        'aggregate_paired_roughness_improvement_95_percent_ci':interval,
        'representative_results':representative,'limitations':info['limitations'],
        'sampling':'Exact Java Random replay;16 candidates; one masked inference per active voice; four parameter heads with integer bin jitter; uniform final candidate selection.'}
    (ARTIFACTS/'sound_model_independent_validation.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'checks_passed':bool(passed),'holdout':quantized,'aggregate_improvement_ci':interval,'probability_error':probability_error}),flush=True)
    if not passed:raise SystemExit('Sound model validation failed')


if __name__=='__main__':main()
