#!/usr/bin/env python3
"""Train FerroMC's tiny masked chord model from scratch on synthetic data.

There are no pitch classes, scales, chord labels, musical corpora, or pretrained
weights. A clean training candidate is a complete four-note chord. For a random
three-note context, all 64 alternatives for its missing voice form a small
synthetic candidate pool. Duplicate pitches are removed. Candidate weights mix
65% uniform probability with 35% weak spectral-roughness preference. Soft-label
cross entropy learns that distribution, without noisy randomly assigned labels.

The roughness function is an engineering proxy, not a pleasantness claim. It uses
a fixed reference timbre's three partial amplitudes [1, .25, .10], a fixed level,
and a smooth critical-band proximity kernel. Rhythm, tonal theory, and human
preference are deliberately absent. Only the missing voice contributes to loss.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn

from model_acoustics import PITCH_MIN, PITCHES, VOICES, EXPORT_BATCH, PARTIAL_AMPLITUDES, roughness_matrix

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "artifacts"
ASSETS = ROOT / "app/src/main/assets"
ACOUSTIC_MIX = 0.35
ACOUSTIC_STRENGTH = 2.0


class ChordModel(nn.Module):
    def __init__(self):
        super().__init__()
        self.layers = nn.Sequential(
            nn.Linear(256, 128), nn.ReLU(), nn.Linear(128, 128), nn.ReLU(),
            nn.Linear(128, 256),
        )

    def forward(self, x):
        return self.layers(x)


def unique_chords(rng, count):
    # A random permutation rather than pitch sorting: voices have no register role.
    return np.argsort(rng.random((count, PITCHES)), axis=1)[:, :VOICES].astype(np.int64)


def one_hot_context(chords, missing):
    n = len(chords)
    encoded = np.zeros((n, VOICES, PITCHES), dtype=np.float32)
    encoded[np.arange(n)[:, None], np.arange(VOICES)[None, :], chords] = 1
    encoded[np.arange(n), missing, :] = 0
    return encoded.reshape(n, VOICES * PITCHES)


def candidate_targets(chords, missing, roughness):
    n = len(chords)
    visible = np.ones((n, VOICES), dtype=bool)
    visible[np.arange(n), missing] = False
    contexts = chords[visible].reshape(n, VOICES - 1)
    energy = roughness[contexts].mean(axis=1)
    allowed = np.ones((n, PITCHES), dtype=bool)
    allowed[np.arange(n)[:, None], contexts] = False
    logits = -ACOUSTIC_STRENGTH * energy
    logits[~allowed] = -1e9
    preferred = np.exp(logits - logits.max(axis=1, keepdims=True))
    preferred /= preferred.sum(axis=1, keepdims=True)
    uniform = allowed.astype(np.float32) / allowed.sum(axis=1, keepdims=True)
    target = (1 - ACOUSTIC_MIX) * uniform + ACOUSTIC_MIX * preferred
    return target.astype(np.float32), allowed


def make_dataset(seed, count, roughness, split="train"):
    rng = np.random.default_rng(seed)
    chord_batches, missing_batches, collected = [], [], 0
    while collected < count:
        proposal_count = max(4096, (count - collected) * (2 if split == "train" else 12))
        proposed = unique_chords(rng, proposal_count)
        masked = rng.integers(0, VOICES, proposal_count, dtype=np.int64)
        visible = np.ones((proposal_count, VOICES), dtype=bool)
        visible[np.arange(proposal_count), masked] = False
        key = np.sort(proposed[visible].reshape(proposal_count, VOICES - 1), axis=1)
        bucket = ((key[:, 0] * 73856093) ^ (key[:, 1] * 19349663) ^ (key[:, 2] * 83492791)) % 10
        keep = bucket < 8 if split == "train" else bucket == (8 if split == "validation" else 9)
        selected = proposed[keep][:count - collected]
        selected_missing = masked[keep][:count - collected]
        chord_batches.append(selected)
        missing_batches.append(selected_missing)
        collected += len(selected)
    chords = np.concatenate(chord_batches)
    missing = np.concatenate(missing_batches)
    targets, allowed = candidate_targets(chords, missing, roughness)
    return {
        "chords": chords,
        "missing": torch.from_numpy(missing),
        "input": torch.from_numpy(one_hot_context(chords, missing)),
        "targets": torch.from_numpy(targets),
        "allowed": torch.from_numpy(allowed),
    }


@torch.no_grad()
def evaluate(model, dataset):
    probabilities = []
    for start in range(0, len(dataset["input"]), 1024):
        stop = start + 1024
        logits = model(dataset["input"][start:stop]).reshape(-1, VOICES, PITCHES)
        missing = dataset["missing"][start:stop]
        chosen = logits[torch.arange(len(logits)), missing]
        chosen = chosen.masked_fill(~dataset["allowed"][start:stop], -1e9)
        probabilities.append(chosen.softmax(-1))
    predicted = torch.cat(probabilities).clamp_min(1e-12)
    target = dataset["targets"]
    target_entropy = -(target * target.clamp_min(1e-12).log()).sum(-1).mean().item()
    ce = -(target * predicted.log()).sum(-1).mean().item()
    uniform_ce = math.log(PITCHES - (VOICES - 1))
    return {
        "cross_entropy_nats": ce,
        "target_entropy_nats": target_entropy,
        "kl_to_target_nats": ce - target_entropy,
        "uniform_allowed_cross_entropy_nats": uniform_ce,
        "uniform_allowed_kl_nats": uniform_ce - target_entropy,
        "kl_reduction_vs_uniform_fraction": 1 - (ce - target_entropy) / (uniform_ce - target_entropy),
    }


def chord_scores(chords, roughness):
    scores = np.zeros(len(chords), dtype=np.float64)
    for a in range(VOICES):
        for b in range(a + 1, VOICES):
            scores += roughness[chords[:, a], chords[:, b]]
    return scores / 6


@torch.no_grad()
def sample_chords(model, initial, rng):
    current = initial.copy()
    for voice in range(VOICES):
        for start in range(0, len(current), 1024):
            chords = current[start:start + 1024]
            missing = np.full(len(chords), voice, dtype=np.int64)
            inputs = torch.from_numpy(one_hot_context(chords, missing))
            logits = model(inputs).reshape(-1, VOICES, PITCHES)[:, voice].numpy().copy()
            for other in range(VOICES):
                if other != voice:
                    logits[np.arange(len(chords)), chords[:, other]] = -1e9
            probabilities = np.exp(logits - logits.max(axis=1, keepdims=True))
            probabilities /= probabilities.sum(axis=1, keepdims=True)
            draws = rng.random(len(chords))[:, None]
            sampled = (probabilities.cumsum(axis=1) < draws).sum(axis=1)
            current[start:start + len(chords), voice] = np.minimum(sampled, PITCHES - 1)
    return current


def sample_metrics(chords, roughness):
    scores = chord_scores(chords, roughness)
    counts = np.bincount(chords.ravel(), minlength=PITCHES).astype(np.float64)
    probabilities = counts / counts.sum()
    entropy = -(probabilities * np.log(probabilities.clip(1e-12))).sum() / math.log(PITCHES)
    return {
        "roughness_mean": float(scores.mean()),
        "roughness_standard_error": float(scores.std() / math.sqrt(len(scores))),
        "roughness_median": float(np.median(scores)),
        "normalized_pitch_entropy": float(entropy),
        "pitch_support": int((counts > 0).sum()),
        "unique_unordered_chords_fraction": float(len(np.unique(np.sort(chords, axis=1), axis=0)) / len(chords)),
        "duplicate_pitch_chords": int(np.sum(np.any(np.diff(np.sort(chords, axis=1), axis=1) == 0, axis=1))),
    }


def export_float_onnx(model, path):
    # Direct export keeps exactly Gemm/ReLU, without framework tracing artifacts.
    import onnx
    from onnx import TensorProto, helper, numpy_helper

    initializers, nodes = [], []
    previous = "chord_context"
    linear_indices = [0, 2, 4]
    for number, layer_index in enumerate(linear_indices):
        layer = model.layers[layer_index]
        weight_name, bias_name = f"linear{number}_weight", f"linear{number}_bias"
        initializers.extend([
            numpy_helper.from_array(layer.weight.detach().numpy(), weight_name),
            numpy_helper.from_array(layer.bias.detach().numpy(), bias_name),
        ])
        output = "pitch_logits" if number == 2 else f"linear{number}_output"
        nodes.append(helper.make_node("Gemm", [previous, weight_name, bias_name], [output],
                                      name=f"linear{number}", transB=1))
        if number < 2:
            relu_output = f"relu{number}_output"
            nodes.append(helper.make_node("Relu", [output], [relu_output], name=f"relu{number}"))
            previous = relu_output
    graph = helper.make_graph(nodes, "sori_seed_masked_chord", [
        helper.make_tensor_value_info("chord_context", TensorProto.FLOAT, [EXPORT_BATCH, 256])
    ], [helper.make_tensor_value_info("pitch_logits", TensorProto.FLOAT, [EXPORT_BATCH, 256])], initializers)
    exported = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)], producer_name="sori-seed")
    exported.ir_version = 9
    exported = onnx.shape_inference.infer_shapes(exported)
    onnx.checker.check_model(exported)
    onnx.save(exported, path)


def quantize_and_validate(float_path, quantized_path, validation):
    import onnx
    import onnxruntime as ort
    from onnxruntime.quantization import CalibrationDataReader, QuantFormat, QuantType, quantize_static

    class Calibration(CalibrationDataReader):
        def __init__(self):
            self.index = 0

        def get_next(self):
            if self.index >= 2048:
                return None
            result = {"chord_context": validation["input"][self.index:self.index + EXPORT_BATCH].numpy()}
            self.index += EXPORT_BATCH
            return result

    quantize_static(str(float_path), str(quantized_path), Calibration(), quant_format=QuantFormat.QDQ,
                    activation_type=QuantType.QUInt8, weight_type=QuantType.QUInt8,
                    per_channel=False, op_types_to_quantize=["Gemm", "Relu"],
                    extra_options={"ActivationSymmetric": False, "WeightSymmetric": False,
                                   "DedicatedQDQPair": True, "QDQKeepRemovableActivations": True})
    onnx.checker.check_model(onnx.load(quantized_path))
    sessions = [ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
                for path in [float_path, quantized_path]]
    errors, probability_errors, argmax_matches = [], [], []
    for start in range(2048, 4096, EXPORT_BATCH):
        inputs = {"chord_context": validation["input"][start:start + EXPORT_BATCH].numpy()}
        outputs = [session.run(["pitch_logits"], inputs)[0] for session in sessions]
        errors.append(float(np.max(np.abs(outputs[0] - outputs[1]))))
        probabilities = [torch.from_numpy(output.reshape(-1, VOICES, PITCHES)).softmax(-1).numpy() for output in outputs]
        probability_errors.append(float(np.mean(np.abs(probabilities[0] - probabilities[1]))))
        argmax_matches.append(float(np.mean(outputs[0].reshape(-1, VOICES, PITCHES).argmax(-1) ==
                                            outputs[1].reshape(-1, VOICES, PITCHES).argmax(-1))))
    return {"max_absolute_logit_error": max(errors),
            "mean_absolute_probability_error": float(np.mean(probability_errors)),
            "argmax_agreement": float(np.mean(argmax_matches)),
            "runtime_verification": "ONNX Runtime CPU numerical comparison only; not a phone/NPU verification",
            "operators": sorted({node.op_type for node in onnx.load(quantized_path).graph.node})}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--epochs", type=int, default=36)
    parser.add_argument("--train-contexts", type=int, default=65536)
    parser.add_argument("--validation-contexts", type=int, default=8192)
    parser.add_argument("--batch-size", type=int, default=1024)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--seed", type=int, default=20261021)
    args = parser.parse_args()
    if args.validation_contexts < 4096:
        parser.error("at least 4096 validation contexts required for separate calibration and numerical checks")
    ARTIFACTS.mkdir(exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(args.threads)
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    start_time = time.time()
    roughness, roughness_scale = roughness_matrix()
    train = make_dataset(args.seed, args.train_contexts, roughness)
    validation = make_dataset(args.seed + 1, args.validation_contexts, roughness, split="validation")
    model = ChordModel()
    parameter_count = sum(parameter.numel() for parameter in model.parameters())
    assert parameter_count == 82432 and parameter_count <= 100000
    optimizer = torch.optim.AdamW(model.parameters(), lr=0.002, weight_decay=0.0001)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, args.epochs, eta_min=0.00015)
    initial_metrics = evaluate(model, validation)
    history = [{"epoch": 0, **initial_metrics}]
    print(json.dumps({"parameters": parameter_count, "initial_validation": initial_metrics}), flush=True)
    best_kl, best_state = float("inf"), None
    for epoch in range(1, args.epochs + 1):
        model.train()
        permutation = torch.randperm(args.train_contexts)
        total_loss = 0
        for offset in range(0, args.train_contexts, args.batch_size):
            indices = permutation[offset:offset + args.batch_size]
            outputs = model(train["input"][indices]).reshape(-1, VOICES, PITCHES)
            logits = outputs[torch.arange(len(indices)), train["missing"][indices]]
            loss = -(train["targets"][indices] * logits.log_softmax(-1)).sum(-1).mean()
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            optimizer.step()
            total_loss += loss.item() * len(indices)
        scheduler.step()
        model.eval()
        metrics = evaluate(model, validation)
        history.append({"epoch": epoch, "training_loss": total_loss / args.train_contexts, **metrics})
        if metrics["kl_to_target_nats"] < best_kl:
            best_kl = metrics["kl_to_target_nats"]
            best_state = {key: value.detach().clone() for key, value in model.state_dict().items()}
        if epoch == 1 or epoch % 4 == 0 or epoch == args.epochs:
            print(json.dumps(history[-1]), flush=True)
    model.load_state_dict(best_state)
    model.eval()
    final_metrics = evaluate(model, validation)
    rng = np.random.default_rng(args.seed + 2)
    initial_chords = unique_chords(rng, 16384)
    generated = sample_chords(model, initial_chords, rng)
    baseline_metrics = sample_metrics(initial_chords, roughness)
    generated_metrics = sample_metrics(generated, roughness)
    reduction = 1 - generated_metrics["roughness_mean"] / baseline_metrics["roughness_mean"]
    torch.save({"state_dict": model.state_dict(), "args": vars(args), "parameter_count": parameter_count},
               ARTIFACTS / "model_checkpoint.pt")
    float_path = ARTIFACTS / "model_float.onnx"
    export_float_onnx(model, float_path)
    quantized_path = ASSETS / "seed_model.onnx"
    quantization_metrics = quantize_and_validate(float_path, quantized_path, validation)
    hash_value = hashlib.sha256(quantized_path.read_bytes()).hexdigest()
    passed = (final_metrics["kl_reduction_vs_uniform_fraction"] > 0.25 and reduction > 0.015 and
              generated_metrics["normalized_pitch_entropy"] > 0.97 and generated_metrics["pitch_support"] == 64 and
              generated_metrics["unique_unordered_chords_fraction"] > 0.94 and
              quantization_metrics["mean_absolute_probability_error"] < 0.001)
    report = {
        "quality_checks_passed": passed, "training_seconds": round(time.time() - start_time, 2),
        "initial_validation": initial_metrics, "final_validation": final_metrics,
        "uniform_chord_baseline": baseline_metrics, "four_pass_generation": generated_metrics,
        "roughness_mean_reduction_fraction": reduction, "quantization": quantization_metrics,
        "history": history,
        "limitations": ["Acoustic proxy improvement does not establish subjective pleasantness or creativity.",
                        "Synthetic data only; no human preference supervision.",
                        "Desktop numerical checks do not establish Android HTP delegation.",
                        "Only single-masked-voice inputs are trained; all-zero generation is unsupported."]
    }
    info = {
        "name": "FerroMC scratch pitch MLP", "version": 1, "parameter_count": parameter_count,
        "architecture": [256, 128, 128, 256], "activations": ["ReLU", "ReLU", "linear"],
        "input_name": "chord_context", "output_name": "pitch_logits",
        "input_shape": [16, 256], "output_shape": [16, 256], "input_dtype": "float32", "output_dtype": "float32",
        "voices": 4, "pitch_min_midi": PITCH_MIN, "pitch_count": PITCHES,
        "mask_encoding": "one 64-value voice block all zero; other blocks one-hot",
        "sampling": "start with 4 distinct random pitches; mask/sample voices 0,1,2,3; exclude existing visible pitches",
        "training_reference_partials": PARTIAL_AMPLITUDES.tolist(), "acoustic_mix": ACOUSTIC_MIX,
        "acoustic_strength": ACOUSTIC_STRENGTH, "roughness_normalization": roughness_scale,
        "training_seed": args.seed, "training_contexts": args.train_contexts,
        "validation_contexts": args.validation_contexts,
        "data_split": "deterministic hash of sorted visible three-note set: train buckets0..7, validation8, independent test9; no context-set leakage",
        "training_candidate_count": args.train_contexts * (PITCHES - VOICES + 1),
        "training_objective": "masked categorical soft-label cross entropy over synthetic uniformly proposed chords, weakly weighted by acoustic roughness",
        "no_pretrained_weights": True, "no_tonal_theory": True, "no_phone_training": True,
        "quantization": "static QDQ uint8 activations / uint8 weights; float32 graph I/O",
        "sha256": hash_value, "file_bytes": quantized_path.stat().st_size,
        "quality_checks_passed": passed, "python_version": platform.python_version(),
        "torch_version": torch.__version__, "generated_at_unix": int(time.time()),
    }
    samples = [{"initial_midi": (initial_chords[i] + PITCH_MIN).tolist(),
                "generated_midi": (generated[i] + PITCH_MIN).tolist(),
                "initial_roughness": float(chord_scores(initial_chords[i:i + 1], roughness)[0]),
                "generated_roughness": float(chord_scores(generated[i:i + 1], roughness)[0])} for i in range(32)]
    (ARTIFACTS / "model_training_report.json").write_text(json.dumps(report, indent=2) + "\n")
    (ARTIFACTS / "model_representative_samples.json").write_text(json.dumps(samples, indent=2) + "\n")
    (ASSETS / "model_info.json").write_text(json.dumps(info, indent=2) + "\n")
    print(json.dumps({"final_validation": final_metrics, "generation": generated_metrics,
                      "roughness_reduction": reduction, "quantization": quantization_metrics,
                      "quality_checks_passed": passed, "model_sha256": hash_value,
                      "training_seconds": report["training_seconds"]}), flush=True)
    if not passed:
        raise SystemExit("Model quality gate failed; inspect artifacts/model_training_report.json")


if __name__ == "__main__":
    main()
