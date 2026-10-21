#!/usr/bin/env python3
"""Compare production Android float preview with the pinned, unchanged Ferro C++ DSP.

Run tools/verify_ferro_export.py --render-dir artifacts/ferro-reference-audio first
to prepare the pinned reference and org.json dependency. Requires Android SDK 35,
JDK 17+, g++, pkg-config, Qt6Core and libsndfile. No device or Gradle invocation.
The comparison covers signal generation, not Android audio hardware/audibility.
"""

import argparse
from array import array
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import shlex
import struct
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
REFERENCE = "ea8a1451ebca207e15cb25361a6a053f9712ac48"
SOURCE = ROOT / ".work/ferro-reference"
WORK = ROOT / ".work/ferro-preview-checks"
EVIDENCE = ROOT / "artifacts/ferro-preview"
FRAMES = 144000  # Export at 120 bpm: one four-beat bar plus one-second tail.
# Fixed before comparing either fixture. The tolerance is below one 16-bit PCM step.
MAX_ABSOLUTE_ERROR = 2e-6
MAX_RMS_ERROR = 2e-7


def run(command):
    return subprocess.run([str(arg) for arg in command], cwd=ROOT, check=True,
                          text=True, capture_output=True).stdout.strip()


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def float32(data):
    if len(data) % 4:
        raise ValueError("Truncated float32 data")
    samples = array("f")
    samples.frombytes(data)
    if sys.byteorder != "little":
        samples.byteswap()
    if not samples or any(not math.isfinite(value) for value in samples):
        raise ValueError("Empty or nonfinite float32 data")
    return samples


def stereo_wave(path):
    raw = path.read_bytes()
    if len(raw) < 12 or raw[:4] != b"RIFF" or raw[8:12] != b"WAVE":
        raise ValueError("Expected RIFF WAVE: " + str(path))
    if struct.unpack_from("<I", raw, 4)[0] + 8 != len(raw):
        raise ValueError("WAVE RIFF size mismatch")
    chunks = {}
    offset = 12
    while offset < len(raw):
        if offset + 8 > len(raw):
            raise ValueError("Truncated WAVE chunk header")
        kind, size = struct.unpack_from("<4sI", raw, offset)
        offset += 8
        if offset + size > len(raw) or kind in chunks:
            raise ValueError("Truncated or repeated WAVE chunk")
        chunks[kind] = raw[offset:offset + size]
        offset += size + (size % 2)
    fmt = chunks.get(b"fmt ", b"")
    if len(fmt) < 16:
        raise ValueError("Missing WAVE format")
    encoding, channels, rate, byte_rate, align, bits = struct.unpack_from("<HHIIHH", fmt)
    if (encoding, channels, rate, byte_rate, align, bits) != (3, 2, 48000, 384000, 8, 32):
        raise ValueError("Expected 48 kHz stereo IEEE float32 WAVE")
    samples = float32(chunks.get(b"data", b""))
    if len(samples) % 2:
        raise ValueError("Incomplete stereo frame")
    return samples[0::2], samples[1::2]


def errors(actual, expected):
    if len(actual) != len(expected) or not actual:
        raise ValueError("Mismatched or empty sample counts")
    differences = [abs(a - b) for a, b in zip(actual, expected)]
    maximum = max(differences)
    return {
        "max_absolute_error": maximum,
        "rms_error": math.sqrt(math.fsum(value * value for value in differences) / len(actual)),
        "max_error_frame": differences.index(maximum),
        "different_float_samples": sum(a != b for a, b in zip(actual, expected)),
    }


def compare(name, record_path, classes, classpath, binary):
    preview = EVIDENCE / (name + ".f32le")
    project = EVIDENCE / (name + ".ferro")
    run(["java", "-cp", classpath, "dev.ferro.seed.FerroPreviewMain", record_path,
         FRAMES, preview, project])
    upstream = json.loads(run([binary, "--expected", record_path, "--render-dir", EVIDENCE, project]))
    if upstream.get("ok") is not True or len(upstream.get("results", [])) != 1:
        raise ValueError("Incomplete upstream C++ validation")
    wave = EVIDENCE / (name + ".wav")
    left, right = stereo_wave(wave)
    actual = float32(preview.read_bytes())
    if len(actual) != FRAMES or len(left) != FRAMES:
        raise ValueError("Unexpected render duration")
    left_error, right_error = errors(actual, left), errors(actual, right)
    channels_error = errors(left, right)
    passed = all(value["max_absolute_error"] <= MAX_ABSOLUTE_ERROR
                 and value["rms_error"] <= MAX_RMS_ERROR for value in (left_error, right_error))
    passed = passed and channels_error["max_absolute_error"] == 0 and max(map(abs, left)) > 0
    return {
        "fixture": name, "passed": passed, "frames": FRAMES, "sample_rate": 48000,
        "record": str(record_path.relative_to(ROOT)), "record_sha256": sha256(record_path),
        "project_sha256": sha256(project), "preview_float32_sha256": sha256(preview),
        "reference_wave": str(wave.relative_to(ROOT)), "reference_wave_sha256": sha256(wave),
        "reference_peak": max(map(abs, left)),
        "reference_rms": math.sqrt(math.fsum(value * value for value in left) / len(left)),
        "preview_vs_left": left_error, "preview_vs_right": right_error,
        "reference_left_vs_right": channels_error, "upstream_loader": upstream,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default_sdk = Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk"))))
    parser.add_argument("--android-jar", type=Path, default=default_sdk / "platforms/android-35/android.jar")
    parser.add_argument("--report", type=Path, default=ROOT / "artifacts/ferro-preview-validation.json")
    args = parser.parse_args()
    report = {"schema": 1, "passed": False, "created_at": datetime.now(timezone.utc).isoformat(),
              "reference_commit": REFERENCE, "results": [],
              "scope": "Host execution of production Java renderFloat versus actual unchanged Ferro C++ DSP; no Android hardware, audibility or NPU performance claim.",
              "master_volume": .7,
              "tolerance": {"max_absolute_error": MAX_ABSOLUTE_ERROR, "rms_error": MAX_RMS_ERROR}}
    try:
        if run(["git", "-C", SOURCE, "rev-parse", "HEAD"]) != REFERENCE:
            raise ValueError("Unexpected Ferro reference revision")
        if run(["git", "-C", SOURCE, "status", "--porcelain"]):
            raise ValueError("Ferro reference has local changes")
        report["reference_sources_unmodified"] = True
        json_jar = ROOT / ".work/ferro-tests/deps/json.jar"
        if sha256(json_jar) != "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed":
            raise ValueError("org.json dependency checksum mismatch")
        if not args.android_jar.is_file():
            raise ValueError("Missing Android SDK compile jar")
        classes = WORK / "classes"
        classes.mkdir(parents=True, exist_ok=True)
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        java_root = ROOT / "app/src/main/java/dev/ferro/seed"
        sources = [java_root / name for name in ("NoteRecord.java", "FerroExporter.java", "TonePlayer.java")]
        sources.append(ROOT / "tools/FerroPreviewMain.java")
        classpath = os.pathsep.join(map(str, (classes, json_jar, args.android_jar)))
        run(["javac", "--release", "17", "-cp", classpath, "-d", classes, *sources])
        flags = shlex.split(run(["pkg-config", "--cflags", "Qt6Core", "sndfile"]))
        libraries = shlex.split(run(["pkg-config", "--libs", "Qt6Core", "sndfile"]))
        binary = WORK / "verify-loader"
        cpp_sources = [ROOT / "tools/ferro_verify_loader.cpp", SOURCE / "src/project.cpp", SOURCE / "src/dsp.cpp"]
        run(["g++", "-std=c++17", "-O2", "-fPIC", *flags, "-I" + str(SOURCE / "src"),
             *cpp_sources, "-o", binary, *libraries])
        report["source_sha256"] = {str(path.relative_to(ROOT)): sha256(path) for path in [*sources, *cpp_sources]}
        report["reference_binary_sha256"] = sha256(binary)
        report["java_version"] = subprocess.run(["java", "-version"], check=True, text=True, capture_output=True).stderr.strip()
        realistic = EVIDENCE / "realistic-record.json"
        realistic.write_text(json.dumps({"id": "realistic-preview", "createdAt": 1700000000000,
            "pitches": [48, 55, 62, 79], "velocities": [96, 82, 111, 64],
            "tones": [18, 47, 83, 61], "decays": [72, 39, 88, 25], "drives": [9, 32, 67, 48],
            "liked": False, "seed": 120035, "modelVersion": "scratch-v2"}, indent=2) + "\n")
        for name, record in (("extreme", ROOT / "artifacts/ferro-fixtures/expected-record.json"),
                             ("realistic", realistic)):
            report["results"].append(compare(name, record, classes, classpath, binary))
        supplied = ROOT / "artifacts/ferro-reference-audio/export-120.wav"
        supplied_left, supplied_right = stereo_wave(supplied)
        fresh_left, fresh_right = stereo_wave(EVIDENCE / "extreme.wav")
        report["existing_golden"] = {"file": str(supplied.relative_to(ROOT)), "sha256": sha256(supplied),
            "left": errors(fresh_left, supplied_left), "right": errors(fresh_right, supplied_right)}
        if any(report["existing_golden"][channel]["max_absolute_error"] != 0 for channel in ("left", "right")):
            raise ValueError("Previously supplied golden differs from fresh actual C++ output")
        report["passed"] = all(item["passed"] for item in report["results"])
        if not report["passed"]:
            raise ValueError("Production Java preview differs from Ferro beyond the fixed tolerance")
    except Exception as error:
        report["passed"] = False
        report["error"] = str(error)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
