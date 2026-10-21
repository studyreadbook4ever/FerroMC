#!/usr/bin/env python3
"""Verify S24 HTP in one detached job; --resume-run only collects that same run."""
import argparse
import csv
import hashlib
import io
import importlib.util
import json
import math
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
_detached_spec = importlib.util.spec_from_file_location("ferromc_detached_ui", Path(__file__).with_name("run_device_ui.py"))
detached = importlib.util.module_from_spec(_detached_spec)
_detached_spec.loader.exec_module(detached)
PACKAGE = "dev.ferro.seed"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
HTP_CLASS = PACKAGE + ".HtpDeviceTest"
TRANSIENT = ("device offline", "device not found", "no devices/emulators found",
             "device unauthorized", "device still authorizing", "unauthorized",
             "connection reset", "connection closed", "transport error",
             "transport is closing", "error: closed", "cannot connect")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def validate_evidence(report, diagnostic, profile, sound_profile, expected_run_id,
                      expected_model_sha, expected_sound_model_sha):
    """Validate independent report and raw dispatch events; no claimed flag alone is sufficient."""
    require(report.get("run_id") == expected_run_id, "Device report is stale or belongs to another run")
    require(report.get("status") == "passed" and report.get("device_execution_verified") is True,
            "Device instrumentation did not pass")
    require(report.get("schema_version") == 2, "Expected pitch and sound verification schema 2")
    require(report.get("model_inference_calls") == 128, "Expected 128 actual neural calls")
    require(report.get("sound_model_inference_calls") == 95, "Expected 95 actual sound calls")
    require(report.get("total_model_inference_calls") == 223, "Expected 223 combined model calls")
    generations = report.get("generations", [])
    require(len(generations) == 32, "Expected 32 generated chords")
    for item in generations:
        notes = item.get("pitches", [])
        count = item.get("count")
        require(count in (2, 3, 4) and len(notes) == count, "Invalid generated note count")
        require(item.get("model_calls") == 4, "A chord bypassed one or more model passes")
        require(item.get("sound_model_calls") == count, "A note bypassed sound-model inference")
        for key, minimum, maximum in (("velocities", 16, 127), ("tones", 0, 100),
                                      ("decays", 0, 100), ("drives", 0, 100)):
            values = item.get(key, [])
            require(len(values) == count and all(type(value) is int and minimum <= value <= maximum
                                                 for value in values), "Invalid generated " + key)
        require(all(type(note) is int for note in notes), "Non-integer generated pitch")
        require(notes == sorted(set(notes)), "Generated pitches are duplicated or unsorted")
        low, high = item.get("low_pitch"), item.get("high_pitch")
        require((low, high) in ((36, 71), (48, 83), (36, 99)), "Unexpected test range")
        require(all(low <= note <= high for note in notes), "Pitch exceeds requested range")
    require(diagnostic.get("status") == "htp_verified", "Engine did not verify HTP")
    require(diagnostic.get("model_sha256") == expected_model_sha, "Phone model differs from supplied APK")
    require(diagnostic.get("sound_model_sha256") == expected_sound_model_sha,
            "Phone sound model differs from supplied APK")
    require(diagnostic.get("cpu_graph_fallback_disabled") is True, "CPU graph fallback is not disabled")
    require(diagnostic.get("io_quantization_cpu_offload") is False, "Graph I/O was allowed on CPU")
    require(diagnostic.get("inference_count") == 128, "Engine invocation count differs from test")
    require(diagnostic.get("sound_inference_count") == 95, "Sound invocation count differs from test")
    require(diagnostic.get("total_inference_count") == 223, "Combined invocation count differs from test")
    require(diagnostic.get("warmup_model_calls") == 2, "Both models must warm up once")
    require(diagnostic.get("shape") == [16, 256], "Model shape mismatch")
    require(diagnostic.get("sound_input_shape") == [16, 32], "Sound input shape mismatch")
    require(diagnostic.get("sound_output_shape") == [16, 64], "Sound output shape mismatch")
    for key in ("qnn_options", "sound_qnn_options"):
        qnn = diagnostic.get(key, {})
        require(qnn.get("backend_path", "").endswith("/libQnnHtp.so"), key + ": selected backend is not HTP")
        require(qnn.get("soc_model") == "57" and qnn.get("htp_arch") == "75", key + ": target is not SM8650/V75")
        require(qnn.get("offload_graph_io_quantization") == "0", key + ": I/O CPU offload option is incorrect")
    pitch_nodes = validate_dispatch_profile(profile, diagnostic, "")
    sound_nodes = validate_dispatch_profile(sound_profile, diagnostic, "sound_")
    embedded = report.get("npu_diagnostics", {})
    require(embedded == diagnostic, "Standalone and embedded device diagnostics differ")
    return {"qnn_node_events": pitch_nodes, "sound_qnn_node_events": sound_nodes,
            "model_calls": 128, "sound_model_calls": 95, "total_model_calls": 223, "generated_chords": 32}


def validate_dispatch_profile(profile, diagnostic, prefix):
    qnn_nodes = 0
    require(isinstance(profile, list), "Malformed ORT profile")
    for event in profile:
        if not isinstance(event, dict) or event.get("cat") != "Node":
            continue
        name = event.get("name", "")
        if name.endswith(("_fence_before", "_fence_after")):
            continue
        provider = event.get("args", {}).get("provider")
        require(provider == "QNNExecutionProvider", f"Non-QNN or missing provider in executed node {name}: {provider}")
        qnn_nodes += 1
    require(qnn_nodes > 0, "No actual QNN graph execution in ORT profile")
    require(qnn_nodes == diagnostic.get(prefix + "profile_qnn_node_events"), "Profile event count mismatch")
    require(diagnostic.get(prefix + "profile_non_qnn_node_events") == 0, "Non-QNN graph execution detected")
    return qnn_nodes


def validate_qnn_csv(content, expected_executions):
    rows = list(csv.DictReader(io.StringIO(content.decode("utf-8-sig"))))
    executions = [row for row in rows if row.get("Event Identifier") == "QNN (execute) time"]
    require(len(executions) == expected_executions,
            "QNN detailed profile execute count differs from model calls plus warmup")
    for row in executions:
        require(row.get("Unit of Measurement") == "US", "Unknown QNN profiling time unit")
        timing = float(row.get("Time", "nan"))
        require(math.isfinite(timing) and timing >= 0, "Invalid QNN execution timing")
    return len(executions)


class Device(detached.Device):
    def __init__(self, adb, serial, timeout, output):
        super().__init__(adb, serial, output, timeout)
        self.log = output / "adb-commands.jsonl"

    def call(self, args, label=None, max_seconds=45, retry=True):
        label = label or " ".join(args[:3])
        while True:
            remaining = self.deadline - time.monotonic()
            if remaining <= 0:
                raise detached.Pending("Device verification timeout while " + label)
            command = [self.adb, "-s", self.serial, *args]
            started = time.monotonic()
            try:
                result = subprocess.run(command, capture_output=True,
                                        timeout=min(max_seconds, remaining))
            except subprocess.TimeoutExpired as error:
                if retry and self.deadline - time.monotonic() > 2:
                    print("Retrying timed-out device command: " + label, flush=True)
                    time.sleep(2)
                    continue
                raise detached.Pending("ADB command timed out while " + label) from error
            message = (result.stdout + result.stderr).decode("utf-8", errors="replace")
            with self.log.open("a") as out:
                out.write(json.dumps({"step": label, "args": args, "returncode": result.returncode,
                                      "elapsed_seconds": time.monotonic() - started,
                                      "failed_stdout": result.stdout[:16000].decode("utf-8", errors="replace") if result.returncode else "",
                                      "stderr": result.stderr.decode("utf-8", errors="replace")}) + "\n")
            if result.returncode == 0:
                return result.stdout
            transient = (any(token in message.lower() for token in TRANSIENT)
                         or re.search(r"device .*not found", message.lower()) is not None)
            if not retry:
                raise detached.Pending("Launch acknowledgement unavailable; resume the same run: " + message.strip())
            if not transient:
                raise RuntimeError(label + ": " + message.strip())
            print("Waiting for selected device: " + label, flush=True)
            time.sleep(min(2, max(0, self.deadline - time.monotonic())))

    def property(self, name):
        return self.call(["shell", "getprop", name], "read " + name).decode().strip()

    def private(self, filename):
        require(re.fullmatch(r"[A-Za-z0-9_.-]+", filename), "Unsafe private evidence filename")
        return self.call(["exec-out", "run-as", PACKAGE, "cat", "files/" + filename],
                         "collect " + filename, retry=True)


def load_htp_resume(output, serial):
    metadata = detached.load_resume(output, serial)
    require(metadata.get("kind") == "htp-device-validation", "Resume directory is not an HTP verification run")
    require(metadata["classes"] == [HTP_CLASS] and metadata["expected_tests"] == 1,
            "Resume selection is not the original single HtpDeviceTest")
    for key in ("apk_sha256", "test_apk_sha256", "model_sha256", "sound_model_sha256"):
        require(isinstance(metadata.get(key), str) and re.fullmatch(r"[0-9a-f]{64}", metadata[key]),
                "Missing original APK/model fingerprint: " + key)
    return metadata


def evidence_path(output):
    try:
        return str(output.relative_to(ROOT))
    except ValueError:
        return str(output)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Exact physical S24 Ultra ADB serial")
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--test-apk", type=Path,
                        default=ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--resume-run", type=Path,
                        help="Existing evidence directory; no install, reset, or test relaunch")
    parser.add_argument("--timeout", type=float, default=300, help="Total seconds, including reconnect retries")
    args = parser.parse_args(argv)
    require(re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial) is not None, "Invalid serial")
    require(not args.serial.startswith("emulator-"), "Emulators are not HTP device validation targets")
    require(args.timeout > 0, "Timeout must be positive")
    if args.resume_run:
        output = args.resume_run.resolve()
        metadata = load_htp_resume(output, args.serial)
    else:
        require(args.apk.is_file() and args.test_apk.is_file(), "Build both APKs before running device validation")
        run_id = uuid.uuid4().hex
        output = ROOT / "artifacts/device" / args.serial / run_id
        output.mkdir(parents=True, exist_ok=False)
        with zipfile.ZipFile(args.apk) as apk:
            model_sha = hashlib.sha256(apk.read("assets/seed_model.onnx")).hexdigest()
            sound_sha = hashlib.sha256(apk.read("assets/sound_model.onnx")).hexdigest()
        metadata = {"schema_version": 1, "kind": "htp-device-validation", "run_id": run_id,
                    "serial": args.serial, "remote_directory": detached.remote_directory(run_id),
                    "runner": RUNNER, "classes": [HTP_CLASS], "expected_tests": 1,
                    "launch_attempted": False, "created_at_epoch_ms": int(time.time() * 1000),
                    "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                    "test_apk_sha256": hashlib.sha256(args.test_apk.read_bytes()).hexdigest(),
                    "model_sha256": model_sha, "sound_model_sha256": sound_sha}
        detached.save_json(output / "run.json", metadata)
    run_id = metadata["run_id"]
    expected_model_sha = metadata["model_sha256"]
    expected_sound_model_sha = metadata["sound_model_sha256"]
    summary = {"run_id": run_id, "serial": args.serial, "status": "running",
               "device_execution_verified": False, "started_at_epoch_ms": metadata["created_at_epoch_ms"],
               "remote_directory": metadata["remote_directory"], "resumed": bool(args.resume_run),
               **{key: metadata[key] for key in ("apk_sha256", "test_apk_sha256", "model_sha256", "sound_model_sha256")}}
    device = Device(args.adb, args.serial, args.timeout, output)
    try:
        print("Checking physical device " + args.serial, flush=True)
        device.call(["get-state"], "wait for device")
        properties = {key: device.property(key) for key in
                      ("ro.product.model", "ro.product.cpu.abi", "ro.kernel.qemu", "ro.soc.model")}
        summary["properties"] = properties
        require(properties["ro.kernel.qemu"] != "1", "Emulator refused")
        require(properties["ro.product.model"].startswith("SM-S928"), "Expected Samsung S24 Ultra (SM-S928*)")
        require(properties["ro.product.cpu.abi"] == "arm64-v8a", "Expected physical ARM64 device")
        if properties["ro.soc.model"]:
            require("SM8650" in properties["ro.soc.model"], "Unexpected SoC for S24 Ultra")
        if not args.resume_run:
            for path, label in ((args.apk, "install app"), (args.test_apk, "install test APK")):
                print(label, flush=True)
                install = device.call(["install", "-r", "-t", str(path.resolve())], label, max_seconds=90)
                (output / ("app-install.txt" if path == args.apk else "test-install.txt")).write_bytes(install)
                require("Success" in install.decode(errors="replace"), label + " did not report Success")
            # Reset only before the first launch. Resuming is entirely read-only on the phone.
            device.call(["shell", "run-as", PACKAGE, "rm", "-f", "files/htp-device-validation.json",
                         "files/npu-diagnostics.json", "files/npu-profile.csv", "files/npu-profile_qnn.log",
                         "files/sound-npu-profile.csv", "files/sound-npu-profile_qnn.log"],
                        "reset diagnostic files")
            print("Launching detached HtpDeviceTest (32 chords / 128 pitch + 95 sound calls)", flush=True)
            detached.launch_once(device, metadata, output / "run.json")
        instrumentation_error = None
        try:
            summary["detached_job"] = detached.wait_and_collect(device, metadata, 2)
        except detached.Pending:
            raise
        except RuntimeError as error:
            instrumentation_error = error
        # Retrieve before asserting test success so useful failures are preserved as evidence.
        report_bytes = device.private("htp-device-validation.json")
        (output / "htp-device-validation.json").write_bytes(report_bytes)
        report = json.loads(report_bytes)
        try:
            diagnostic_bytes = device.private("npu-diagnostics.json")
            (output / "npu-diagnostics.json").write_bytes(diagnostic_bytes)
            diagnostic = json.loads(diagnostic_bytes)
        except detached.Pending:
            raise
        except RuntimeError:
            diagnostic = {}
        if instrumentation_error is not None:
            raise instrumentation_error
        profiles = []
        for key, prefix in (("ort_profile", "ort-htp-proof"), ("sound_ort_profile", "sound-ort-htp-proof")):
            profile_name = Path(diagnostic.get(key, "")).name
            require(profile_name.startswith(prefix) and profile_name.endswith(".json"),
                    "Missing ORT profile filename: " + key)
            profile_bytes = device.private(profile_name)
            (output / profile_name).write_bytes(profile_bytes)
            profiles.append(json.loads(profile_bytes))
        summary["validation"] = validate_evidence(report, diagnostic, profiles[0], profiles[1],
                                                  run_id, expected_model_sha, expected_sound_model_sha)
        for prefix, expected in (("", 129), ("sound-", 96)):
            csv_name = prefix + "npu-profile.csv"
            qnn_csv = device.private(csv_name)
            (output / csv_name).write_bytes(qnn_csv)
            summary["validation"][prefix + "qnn_csv_executions"] = validate_qnn_csv(qnn_csv, expected)
            try:
                binary_name = prefix + "npu-profile_qnn.log"
                (output / binary_name).write_bytes(device.private(binary_name))
                summary[prefix + "qnn_binary_profile_collected"] = True
            except detached.Pending:
                raise
            except RuntimeError:
                # Optional companion; each model's CSV and raw ORT proof are mandatory.
                summary[prefix + "qnn_binary_profile_collected"] = False
        summary["status"] = "passed"
        summary["device_execution_verified"] = True
        print("PASS: actual S24 Ultra HTP execution; 32 chords, 223 model calls, both graphs QNN-only.", flush=True)
    except (detached.Pending, KeyboardInterrupt) as error:
        summary["status"] = "pending_resume" if metadata.get("launch_attempted") else "failed_before_launch"
        summary["error"] = str(error) or "Host collection interrupted; remote job was not stopped"
        print(summary["status"] + ": " + summary["error"], file=sys.stderr, flush=True)
    except Exception as error:
        summary["status"] = "failed"
        summary["error"] = str(error)
        print("FAIL: " + str(error), file=sys.stderr, flush=True)
    finally:
        summary["finished_at_epoch_ms"] = int(time.time() * 1000)
        detached.save_json(output / "summary.json", summary)
        detached.save_json(output.parent / "latest-summary.json", {**summary, "evidence_directory": evidence_path(output)})
        print("Evidence: " + evidence_path(output), flush=True)
        if summary["status"] != "passed" and metadata.get("launch_attempted"):
            print("Collect the SAME run: " + shlex.join([sys.executable, str(Path(__file__).resolve()),
                  "--serial", args.serial, "--resume-run", str(output), "--timeout", str(args.timeout)]), flush=True)
    return 0 if summary["status"] == "passed" else 2 if summary["status"] == "pending_resume" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, OSError, ValueError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
