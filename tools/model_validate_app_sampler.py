#!/usr/bin/env python3
"""Replay ChordSampler's actual sampling recipe against the quantized ONNX model.

This is desktop CPU numerical validation, NOT an Android HTP execution test.
Java Random's state transitions, sorted initialization, four single-voice passes,
16 candidates, the temperature/broad-weight formulas, final candidate selection,
and partial Fisher-Yates selection for counts 2/3 match the app. The control uses
the same sampler and seeds with uniform neural logits.
"""

import argparse
import hashlib
import json
import math
from pathlib import Path

import numpy as np
import onnxruntime as ort

from model_acoustics import roughness_matrix

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "artifacts"
ASSETS = ROOT / "app/src/main/assets"


class JavaRandom:
    def __init__(self, seed):
        self.state = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def next(self, bits):
        self.state = (self.state * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        return self.state >> (48 - bits)

    def next_int(self, bound):
        if bound & (bound - 1) == 0:
            return (bound * self.next(31)) >> 31
        while True:
            bits = self.next(31)
            value = bits % bound
            if bits - value + bound - 1 < (1 << 31):
                return value

    def next_double(self):
        return ((self.next(26) << 27) + self.next(27)) / float(1 << 53)


def select_reduced_count(unsorted_chord, count, rng_state):
    # Fork immediately after the final candidate draw. In the app, each requested
    # count starts at this exact state and shuffles the still-unsorted voice array.
    rng = JavaRandom(0)
    rng.state = rng_state
    result = unsorted_chord.copy()
    for index in range(count):
        chosen = index + rng.next_int(4 - index)
        result[index], result[chosen] = result[chosen], result[index]
    return np.sort(result[:count])


def generate(session, seed, low, high, exploration, return_subsets=False):
    rng = JavaRandom(seed)
    candidates = np.empty((16, 4), dtype=np.int64)
    for chord in candidates:
        for voice in range(4):
            while True:
                pitch = low + rng.next_int(high - low + 1)
                if pitch not in chord[:voice]:
                    chord[voice] = pitch
                    break
        chord.sort()
    temperature = .75 + max(0, min(1, exploration)) * .9
    for voice in range(4):
        context = np.zeros((16, 4, 64), dtype=np.float32)
        for other in range(4):
            if other != voice:
                context[np.arange(16), other, candidates[:, other] - 36] = 1
        if session is None:
            logits = np.zeros((16, 4, 64), dtype=np.float32)
        else:
            logits = session.run(["pitch_logits"], {"chord_context": context.reshape(16, 256)})[0].reshape(16, 4, 64)
        if not np.isfinite(logits).all():
            raise RuntimeError("Non-finite quantized model output")
        for batch in range(16):
            allowed = [pitch for pitch in range(low, high + 1)
                       if all(other == voice or candidates[batch, other] != pitch for other in range(4))]
            values = [float(logits[batch, voice, pitch - 36]) / temperature for pitch in allowed]
            maximum = max(values)
            weights = [math.exp(value - maximum) + .02 * exploration for value in values]
            draw = rng.next_double() * sum(weights)
            selected = allowed[-1]
            for pitch, weight in zip(allowed, weights):
                draw -= weight
                if draw <= 0:
                    selected = pitch
                    break
            candidates[batch, voice] = selected
    unsorted_selected = candidates[rng.next_int(16)].copy()
    selected = np.sort(unsorted_selected)
    if return_subsets:
        subsets = {count: select_reduced_count(unsorted_selected, count, rng.state) for count in (2, 3)}
        return selected, np.sort(candidates, axis=1), subsets
    return selected, np.sort(candidates, axis=1)


def scores(chords, roughness):
    voices = chords.shape[1]
    output = np.zeros(len(chords), dtype=np.float64)
    for a in range(voices):
        for b in range(a + 1, voices):
            output += roughness[chords[:, a] - 36, chords[:, b] - 36]
    return output / (voices * (voices - 1) / 2)


def metrics(chords, roughness, low, high):
    rough = scores(chords, roughness)
    counts = np.bincount(chords.ravel() - low, minlength=high - low + 1).astype(np.float64)
    p = counts / counts.sum()
    return {
        "samples": len(chords), "roughness_mean": float(rough.mean()),
        "normalized_pitch_entropy": float(-(p * np.log(p.clip(1e-12))).sum() / math.log(len(p))),
        "pitch_support": int((counts > 0).sum()),
        "duplicate_pitch_chords": int(np.any(np.diff(chords, axis=1) == 0, axis=1).sum()),
        "outside_configured_range": int(((chords < low) | (chords > high)).sum()),
        "unique_chord_fraction": float(len(np.unique(chords, axis=0)) / len(chords)),
    }


def compare(neural, uniform, roughness, low, high):
    neural_metrics, uniform_metrics = [metrics(chords, roughness, low, high) for chords in (neural, uniform)]
    delta = scores(uniform, roughness) - scores(neural, roughness)
    standard_error = delta.std(ddof=1) / math.sqrt(len(delta))
    return {
        "neural": neural_metrics, "uniform_logit_control": uniform_metrics,
        "roughness_reduction_fraction": 1 - neural_metrics["roughness_mean"] / uniform_metrics["roughness_mean"],
        "paired_roughness_improvement": float(delta.mean()),
        "paired_improvement_95_percent_ci": [float(delta.mean() - 1.96 * standard_error),
                                              float(delta.mean() + 1.96 * standard_error)],
    }


def rank_selection_metrics(full_chords, selected_chords):
    kept = np.asarray([[pitch in subset for pitch in full]
                       for full, subset in zip(full_chords, selected_chords)], dtype=np.int64)
    masks = (kept * np.asarray([1, 2, 4, 8])).sum(axis=1)
    return {"kept_counts_by_ascending_rank": kept.sum(axis=0).tolist(),
            "omitted_counts_by_ascending_rank": (1 - kept).sum(axis=0).tolist(),
            "distinct_rank_subsets": int(len(np.unique(masks)))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=1024)
    args = parser.parse_args()
    report_path = ARTIFACTS / "model_app_sampler_validation.json"
    previous_report = json.loads(report_path.read_text()) if report_path.exists() else None
    previous_report_sha256 = hashlib.sha256(report_path.read_bytes()).hexdigest() if report_path.exists() else None
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    model_path = ASSETS / "seed_model.onnx"
    session = ort.InferenceSession(str(model_path), options, providers=["CPUExecutionProvider"])
    roughness, _ = roughness_matrix()
    cases = [("default", 48, 83, .55), ("minimum_width", 48, 60, .55),
             ("low_register", 36, 59, .55), ("high_register", 76, 99, .55),
             ("full_range", 36, 99, .55), ("exploration_zero", 48, 83, 0),
             ("exploration_one", 48, 83, 1)]
    results = {}
    for case, low, high, exploration in cases:
        neural, uniform, neural_batches, uniform_batches = [], [], [], []
        neural_subsets, uniform_subsets = {2: [], 3: []}, {2: [], 3: []}
        for index in range(args.samples):
            seed = 202610210000 + index
            selected, batch, selected_subsets = generate(session, seed, low, high, exploration, return_subsets=True)
            control, control_batch, control_subsets = generate(None, seed, low, high, exploration, return_subsets=True)
            neural.append(selected)
            uniform.append(control)
            neural_batches.append(batch)
            uniform_batches.append(control_batch)
            if case == "default":
                for count in (2, 3):
                    neural_subsets[count].append(selected_subsets[count])
                    uniform_subsets[count].append(control_subsets[count])
        neural, uniform = np.asarray(neural), np.asarray(uniform)
        result = {
            "low_pitch": low, "high_pitch": high, "exploration": exploration,
            "temperature": .75 + exploration * .9, "additive_unnormalized_broad_weight": .02 * exploration,
            "selected_four_note_chords": compare(neural, uniform, roughness, low, high),
            "all_sixteen_candidates": compare(np.concatenate(neural_batches), np.concatenate(uniform_batches),
                                               roughness, low, high),
        }
        if case == "default":
            for count, label in ((2, "two"), (3, "three")):
                selected_neural = np.asarray(neural_subsets[count])
                selected_uniform = np.asarray(uniform_subsets[count])
                reduced = compare(selected_neural, selected_uniform, roughness, low, high)
                reduced["rank_selection"] = rank_selection_metrics(neural, selected_neural)
                result[f"selected_{label}_note_chords"] = reduced
            result["representative_seeded_results"] = [
                {"seed": 202610210000 + i, "neural_midi": neural[i].tolist(), "uniform_control_midi": uniform[i].tolist(),
                 "neural_two_midi": neural_subsets[2][i].tolist(), "neural_three_midi": neural_subsets[3][i].tolist(),
                 "uniform_two_midi": uniform_subsets[2][i].tolist(), "uniform_three_midi": uniform_subsets[3][i].tolist()}
                for i in range(8)
            ]
        results[case] = result
        print(json.dumps({"case": case, **result["selected_four_note_chords"]}), flush=True)
    default = results["default"]["selected_four_note_chords"]
    passed = (default["paired_improvement_95_percent_ci"][0] > 0 and
              default["neural"]["normalized_pitch_entropy"] > .97 and
              all(result["selected_four_note_chords"]["neural"]["duplicate_pitch_chords"] == 0 and
                  result["selected_four_note_chords"]["neural"]["outside_configured_range"] == 0
                  for result in results.values()))
    for label in ("two", "three"):
        reduced = results["default"][f"selected_{label}_note_chords"]
        passed = passed and reduced["neural"]["duplicate_pitch_chords"] == 0
        passed = passed and reduced["neural"]["outside_configured_range"] == 0
        passed = passed and all(value > 0 for value in reduced["rank_selection"]["kept_counts_by_ascending_rank"])
        passed = passed and all(value > 0 for value in reduced["rank_selection"]["omitted_counts_by_ascending_rank"])
    model_sha256 = hashlib.sha256(model_path.read_bytes()).hexdigest()
    preservation = {"compared_previous_report": False, "previous_report_sha256": previous_report_sha256}
    if previous_report is not None and previous_report.get("samples_per_case") == args.samples:
        four_note_metrics_identical = all(
            previous_report["cases"][name][field] == results[name][field]
            for name in results for field in ("selected_four_note_chords", "all_sixteen_candidates")
        )
        old_samples = previous_report["cases"]["default"]["representative_seeded_results"]
        new_samples = results["default"]["representative_seeded_results"]
        representative_rng_identical = all(
            before[field] == after[field]
            for before, after in zip(old_samples, new_samples)
            for field in ("seed", "neural_midi", "uniform_control_midi")
        ) and len(old_samples) == len(new_samples)
        preservation.update({"compared_previous_report": True,
                             "model_sha256_unchanged": previous_report["model_sha256"] == model_sha256,
                             "four_note_metrics_bit_identical": four_note_metrics_identical,
                             "four_note_representative_rng_results_identical": representative_rng_identical})
        passed = passed and all(preservation[key] for key in (
            "model_sha256_unchanged", "four_note_metrics_bit_identical", "four_note_representative_rng_results_identical"))
    source = ROOT / "app/src/main/java/dev/ferro/seed/ChordSampler.java"
    tone_source = ROOT / "app/src/main/java/dev/ferro/seed/TonePlayer.java"
    report = {
        "checks_passed": passed,
        "model_sha256": model_sha256,
        "four_note_preservation_check": preservation,
        "sampler_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "tone_player_source_sha256": hashlib.sha256(tone_source.read_bytes()).hexdigest(),
        "training_reference_partials": [1, .25, .10], "samples_per_case": args.samples,
        "neural_inference": "quantized deployed model via desktop ONNX Runtime CPU; no HTP execution claimed",
        "control": "identical Java-compatible RNG seeds and sampler, with constant logits instead of model output",
        "reduced_count_selection": "partial Fisher-Yates on the unsorted selected voice array, using RNG state after the batch-index draw; sort only after selecting count2/count3",
        "checks": "default paired roughness improvement CI excludes zero; default pitch entropy >.97; range and uniqueness in every case; every reduced-count rank both kept and omitted; prior four-note metrics preserved when comparable",
        "limitations": ["Roughness is a spectral proxy, not human pleasantness or creativity.",
                        "All-candidate confidence intervals do not account for within-seed correlation; selected-chord intervals are the primary test.",
                        "No retraining for reduced pitch ranges; restrictions happen after model inference."],
        "cases": results,
    }
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    if not passed:
        raise SystemExit("Actual app sampler quality gate failed")


if __name__ == "__main__":
    main()
