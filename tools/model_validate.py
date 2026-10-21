#!/usr/bin/env python3
"""Independent post-training holdout and ONNX contract checks (desktop only)."""

import hashlib
import json
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch

from train_model import (
    ARTIFACTS, ASSETS, ChordModel, evaluate, make_dataset, roughness_matrix,
)


def main():
    torch.set_num_threads(4)
    checkpoint = torch.load(ARTIFACTS / "model_checkpoint.pt", map_location="cpu", weights_only=True)
    model = ChordModel()
    model.load_state_dict(checkpoint["state_dict"])
    model.eval()
    roughness, _ = roughness_matrix()
    # Separate from training, epoch selection, quantization calibration and sampling.
    dataset = make_dataset(checkpoint["args"]["seed"] + 19, 8192, roughness, split="test")
    float_metrics = evaluate(model, dataset)
    model_path = ASSETS / "seed_model.onnx"
    graph = onnx.load(model_path)
    onnx.checker.check_model(graph)
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    assert session.get_inputs()[0].name == "chord_context"
    assert session.get_outputs()[0].name == "pitch_logits"
    assert session.get_inputs()[0].shape == [16, 256]
    assert session.get_outputs()[0].shape == [16, 256]
    assert session.get_inputs()[0].type == "tensor(float)"
    assert session.get_outputs()[0].type == "tensor(float)"
    outputs = []
    for start in range(0, len(dataset["input"]), 16):
        output = session.run(None, {"chord_context": dataset["input"][start:start + 16].numpy()})[0]
        outputs.append(torch.from_numpy(output).reshape(-1, 4, 64))
    logits = torch.cat(outputs)[torch.arange(8192), dataset["missing"]]
    logits = logits.masked_fill(~dataset["allowed"], -1e9)
    quantized_ce = -(dataset["targets"] * logits.log_softmax(-1)).sum(-1).mean().item()
    quantized_kl = quantized_ce - float_metrics["target_entropy_nats"]
    quantized_reduction = 1 - quantized_kl / float_metrics["uniform_allowed_kl_nats"]
    finite = bool(torch.isfinite(logits[dataset["allowed"]]).all())
    success = (float_metrics["kl_reduction_vs_uniform_fraction"] > 0.25 and
               quantized_reduction > 0.25 and finite)
    report = {
        "checks_passed": success,
        "test_seed": checkpoint["args"]["seed"] + 19,
        "contexts": 8192,
        "parameter_count": sum(p.numel() for p in model.parameters()),
        "float_model": float_metrics,
        "quantized_model": {
            "cross_entropy_nats": quantized_ce,
            "kl_to_target_nats": quantized_kl,
            "kl_reduction_vs_uniform_fraction": quantized_reduction,
            "all_allowed_logits_finite": finite,
        },
        "input_name": "chord_context", "output_name": "pitch_logits",
        "shape": [16, 256], "dtype": "float32",
        "sha256": hashlib.sha256(model_path.read_bytes()).hexdigest(),
        "operators": sorted({node.op_type for node in graph.graph.node}),
        "verification_scope": "Desktop ONNX Runtime CPU only; no Android/NPU execution claimed.",
    }
    (ARTIFACTS / "model_independent_validation.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if not success:
        raise SystemExit("Independent model validation failed")


if __name__ == "__main__":
    main()
