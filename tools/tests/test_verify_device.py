"""Host tests of the acceptance gate, not substitutes for real HTP execution."""
import copy
import importlib.util
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("verify_device", Path(__file__).resolve().parents[1] / "verify_device.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class DeviceEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.diag = {
            "status": "htp_verified", "model_sha256": "abc", "shape": [16, 256],
            "cpu_graph_fallback_disabled": True, "io_quantization_cpu_offload": False,
            "inference_count": 128, "profile_qnn_node_events": 1,
            "profile_non_qnn_node_events": 0,
            "sound_model_sha256": "sound-abc", "sound_input_shape": [16, 32],
            "sound_output_shape": [16, 64], "sound_inference_count": 95,
            "total_inference_count": 223, "warmup_model_calls": 2,
            "sound_profile_qnn_node_events": 1, "sound_profile_non_qnn_node_events": 0,
            "qnn_options": {"backend_path": "/lib/libQnnHtp.so", "soc_model": "57",
                            "htp_arch": "75", "offload_graph_io_quantization": "0"}}
        self.diag["sound_qnn_options"] = copy.deepcopy(self.diag["qnn_options"])
        self.profile = [{"cat": "Node", "name": "QNN_0_kernel_time",
                         "args": {"provider": "QNNExecutionProvider"}}]
        self.sound_profile = copy.deepcopy(self.profile)
        self.report = {"schema_version": 2, "run_id": "fresh", "status": "passed", "device_execution_verified": True,
                       "model_inference_calls": 128, "npu_diagnostics": copy.deepcopy(self.diag),
                       "sound_model_inference_calls": 95, "total_model_inference_calls": 223,
                       "generations": [{"count": count, "pitches": list(range(48, 48 + count)),
                                        "velocities": [96] * count, "tones": [25] * count,
                                        "decays": [50] * count, "drives": [75] * count,
                                        "model_calls": 4, "sound_model_calls": count,
                                        "low_pitch": 48, "high_pitch": 83} for count in (2 + i % 3 for i in range(32))]}

    def validate(self):
        return runner.validate_evidence(self.report, self.diag, self.profile, self.sound_profile,
                                        "fresh", "abc", "sound-abc")

    def test_accepts_complete_consistent_evidence(self):
        self.assertEqual(128, self.validate()["model_calls"])
        self.assertEqual(223, self.validate()["total_model_calls"])

    def test_rejects_cpu_node_despite_claimed_success(self):
        for target in ("profile", "sound_profile"):
            with self.subTest(target=target):
                self.setUp()
                getattr(self, target).append({"cat": "Node", "name": "QDQ", "args": {"provider": "CPUExecutionProvider"}})
                with self.assertRaisesRegex(RuntimeError, "Non-QNN"):
                    self.validate()

    def test_rejects_stale_device_report(self):
        self.report["run_id"] = "previous-run"
        with self.assertRaisesRegex(RuntimeError, "stale"):
            self.validate()

    def test_rejects_different_packaged_model(self):
        for key in ("model_sha256", "sound_model_sha256"):
            with self.subTest(key=key):
                self.setUp()
                self.diag[key] = "other-model"
                with self.assertRaisesRegex(RuntimeError, "differs"):
                    self.validate()

    def test_rejects_no_executed_nodes(self):
        for target in ("profile", "sound_profile"):
            with self.subTest(target=target):
                self.setUp()
                getattr(self, target).clear()
                with self.assertRaisesRegex(RuntimeError, "No actual QNN"):
                    self.validate()

    def test_rejects_incomplete_counts_or_wrong_shape(self):
        cases = (
            ("partial chord passes", lambda: self.report["generations"][5].update(model_calls=3), "bypassed"),
            ("report call count", lambda: self.report.update(model_inference_calls=127), "128 actual"),
            ("engine call count", lambda: self.diag.update(inference_count=127), "invocation count"),
            ("chord count", lambda: self.report["generations"].pop(), "32 generated"),
            ("pitch count", lambda: self.report["generations"][5].update(count=3), "note count"),
            ("tensor shape", lambda: self.diag.update(shape=[1, 256]), "shape mismatch"),
            ("sound passes", lambda: self.report["generations"][5].update(sound_model_calls=0), "bypassed sound"),
            ("sound report count", lambda: self.report.update(sound_model_inference_calls=94), "95 actual sound"),
            ("sound engine count", lambda: self.diag.update(sound_inference_count=94), "Sound invocation"),
            ("sound input shape", lambda: self.diag.update(sound_input_shape=[16, 256]), "Sound input shape"),
            ("sound output shape", lambda: self.diag.update(sound_output_shape=[16, 32]), "Sound output shape"),
            ("out of range tone", lambda: self.report["generations"][0].update(tones=[-1, 0]), "Invalid generated tones"),
            ("missing velocity", lambda: self.report["generations"][0].update(velocities=[]), "Invalid generated velocities"),
        )
        for name, mutate, message in cases:
            with self.subTest(name=name):
                self.setUp()
                mutate()
                with self.assertRaisesRegex(RuntimeError, message):
                    self.validate()

    def test_rejects_io_cpu_offload(self):
        self.diag["io_quantization_cpu_offload"] = True
        with self.assertRaisesRegex(RuntimeError, "I/O"):
            self.validate()
        self.setUp()
        self.diag["sound_qnn_options"]["offload_graph_io_quantization"] = "1"
        with self.assertRaisesRegex(RuntimeError, "I/O"):
            self.validate()

    def test_qnn_csv_requires_each_model_call_plus_its_warmup(self):
        for expected in (129, 96):
            content = ("Event Identifier,Time,Unit of Measurement\n" +
                       "QNN (execute) time,100,US\n" * expected).encode()
            self.assertEqual(expected, runner.validate_qnn_csv(content, expected))
            with self.assertRaisesRegex(RuntimeError, "execute count"):
                runner.validate_qnn_csv(content, expected + 1)

    def test_qnn_csv_rejects_empty_nonfinite_and_wrong_units(self):
        for content in ("Event Identifier,Time,Unit of Measurement\n",
                        "Event Identifier,Time,Unit of Measurement\nQNN (execute) time,nan,US\n",
                        "Event Identifier,Time,Unit of Measurement\nQNN (execute) time,100,CYCLES\n"):
            with self.subTest(content=content), self.assertRaises(RuntimeError):
                runner.validate_qnn_csv(content.encode(), 1)


class DetachedHtpRunTest(unittest.TestCase):
    def test_unacknowledged_launch_then_resume_never_installs_resets_or_launches_again(self):
        fixture = DeviceEvidenceTest()
        fixture.setUp()
        devices = []

        class FakeDevice:
            launch_count = 0
            def __init__(self, adb, serial, timeout, output):
                self.output, self.commands = output, []
                devices.append(self)
            def call(self, args, label=None, **kwargs):
                self.commands.append(args)
                if args[0] == "install": return b"Success\n"
                return b"device\n"
            def property(self, name):
                return {"ro.product.model":"SM-S928B", "ro.product.cpu.abi":"arm64-v8a",
                        "ro.kernel.qemu":"0", "ro.soc.model":"SM8650"}[name]
            def shell(self, script, retry=True):
                self.commands.append(["shell-script", script])
                if retry: raise AssertionError("Launch must disable ADB retries")
                FakeDevice.launch_count += 1
                raise runner.detached.Pending("USB disconnected after remote acceptance")
            def private(self, filename):
                metadata = json.loads((self.output / "run.json").read_text())
                diagnostic = copy.deepcopy(fixture.diag)
                diagnostic.update(model_sha256=metadata["model_sha256"],
                                  sound_model_sha256=metadata["sound_model_sha256"],
                                  ort_profile="ort-htp-proof-test.json",
                                  sound_ort_profile="sound-ort-htp-proof-test.json")
                report = copy.deepcopy(fixture.report)
                report.update(run_id=metadata["run_id"], npu_diagnostics=diagnostic)
                if filename == "htp-device-validation.json": return json.dumps(report).encode()
                if filename == "npu-diagnostics.json": return json.dumps(diagnostic).encode()
                if filename.endswith(".json"): return json.dumps(fixture.profile).encode()
                if filename.endswith(".csv"):
                    count = 96 if filename.startswith("sound-") else 129
                    return ("Event Identifier,Time,Unit of Measurement\n" +
                            "QNN (execute) time,100,US\n" * count).encode()
                return b"optional binary fixture"

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            app, test = root / "app.apk", root / "test.apk"
            with zipfile.ZipFile(app, "w") as apk:
                apk.writestr("assets/seed_model.onnx", b"pitch model fixture")
                apk.writestr("assets/sound_model.onnx", b"sound model fixture")
            test.write_bytes(b"test APK fixture")
            with patch.object(runner, "ROOT", root), patch.object(runner, "Device", FakeDevice), \
                    patch.object(runner.detached, "wait_and_collect",
                                 side_effect=runner.detached.Pending("Polling timed out")), patch("builtins.print"):
                self.assertEqual(2, runner.main(["--serial", "S24", "--apk", str(app), "--test-apk", str(test)]))
            output = next(path for path in (root / "artifacts/device/S24").iterdir() if path.is_dir())
            metadata = json.loads((output / "run.json").read_text())
            self.assertTrue(metadata["launch_attempted"])
            self.assertEqual("acknowledgement_unknown", metadata["launch_state"])
            self.assertEqual("pending_resume", json.loads((output / "summary.json").read_text())["status"])
            self.assertEqual(1, FakeDevice.launch_count)
            # Original APKs can disappear/change; resume validates against persisted fingerprints.
            app.unlink(); test.unlink()
            with patch.object(runner, "ROOT", root), patch.object(runner, "Device", FakeDevice), \
                    patch.object(runner.detached, "launch_once") as launch, \
                    patch.object(runner.detached, "wait_and_collect", return_value={"remote_exit_code":0}) as collect, \
                    patch("builtins.print"):
                self.assertEqual(0, runner.main(["--serial", "S24", "--resume-run", str(output),
                                                 "--apk", str(app), "--test-apk", str(test)]))
                launch.assert_not_called()
                self.assertEqual(metadata["run_id"], collect.call_args.args[1]["run_id"])
            self.assertEqual([["get-state"]], devices[-1].commands)
            self.assertEqual(1, FakeDevice.launch_count)
            self.assertEqual(metadata, json.loads((output / "run.json").read_text()))
            summary = json.loads((output / "summary.json").read_text())
            self.assertEqual("passed", summary["status"])
            self.assertEqual(metadata["run_id"], summary["run_id"])
            self.assertEqual(hashlib.sha256(b"pitch model fixture").hexdigest(), summary["model_sha256"])

    def test_resume_rejects_a_different_test_or_missing_original_fingerprint(self):
        run_id = "1234567890abcdef1234567890abcdef"
        metadata = {"run_id":run_id, "serial":"S24", "kind":"htp-device-validation",
                    "remote_directory":runner.detached.remote_directory(run_id), "runner":runner.RUNNER,
                    "classes":[runner.HTP_CLASS], "expected_tests":1, "launch_attempted":True,
                    **{key:"a"*64 for key in ("apk_sha256","test_apk_sha256","model_sha256","sound_model_sha256")}}
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            (output / "run.json").write_text(json.dumps(metadata))
            self.assertEqual(metadata, runner.load_htp_resume(output, "S24"))
            for change in ({"classes":["dev.ferro.seed.UiFlowTest"]}, {"sound_model_sha256":""},
                           {"kind":"ui-detached"}):
                (output / "run.json").write_text(json.dumps({**metadata, **change}))
                with self.subTest(change=change), self.assertRaises(RuntimeError):
                    runner.load_htp_resume(output, "S24")

    def test_launch_transport_failure_is_not_retried(self):
        with tempfile.TemporaryDirectory() as temporary:
            device = runner.Device("adb", "S24", 30, Path(temporary))
            result = subprocess.CompletedProcess([], 1, b"", b"error: device offline")
            with patch.object(runner.subprocess, "run", return_value=result) as command, \
                    patch.object(runner.time, "sleep") as sleep:
                with self.assertRaises(runner.detached.Pending):
                    device.shell("one detached launch fixture", retry=False)
                command.assert_called_once()
                sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
