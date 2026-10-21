"""Local acceptance/quoting tests; no ADB calls and no physical-device claims."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("run_device_ui", Path(__file__).resolve().parents[1] / "run_device_ui.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
RUN_ID = "1234567890abcdef1234567890abcdef"
CLASS = "dev.ferro.seed.UiFlowTest"


def successful_output():
    return (f"INSTRUMENTATION_STATUS: class={CLASS}\n"
            "INSTRUMENTATION_STATUS: test=historyFlow\n"
            "INSTRUMENTATION_STATUS: numtests=1\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")


class DetachedRunnerTest(unittest.TestCase):
    def test_complete_selected_result_is_accepted(self):
        self.assertEqual([{"class": CLASS, "method": "historyFlow"}],
                         runner.validate_instrumentation(successful_output(), 0, [CLASS], 1))

    def test_terminal_minus_one_alone_cannot_mask_failure(self):
        for output in (successful_output().replace("OK (1 test)", "FAILURES!!!"),
                       successful_output().replace("STATUS_CODE: 0", "STATUS_CODE: -2"),
                       successful_output().replace("STATUS_CODE: 0", "STATUS_CODE: -3")):
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                runner.validate_instrumentation(output, 0, [CLASS], 1)

    def test_mismatched_count_class_or_exit_is_rejected(self):
        for text, code, classes, count in ((successful_output(), 1, [CLASS], 1),
                (successful_output(), 0, [CLASS], 2),
                (successful_output(), 0, ["dev.ferro.seed.UiStateTest"], 1),
                (successful_output().replace("numtests=1", "numtests=2"), 0, [CLASS], 1)):
            with self.subTest(code=code, classes=classes, count=count), self.assertRaises(RuntimeError):
                runner.validate_instrumentation(text, code, classes, count)

    def test_poll_rejects_stale_run_and_accepts_incomplete_claim_without_relaunch(self):
        self.assertEqual("claim_pending", runner.parse_poll("state=claim_pending\n", RUN_ID)["state"])
        with self.assertRaisesRegex(RuntimeError, "another run"):
            runner.parse_poll("run_id=old\nstate=finished\nexit_code=0\n", RUN_ID)
        with self.assertRaisesRegex(RuntimeError, "Invalid remote exit"):
            runner.parse_poll(f"run_id={RUN_ID}\nstate=finished\nexit_code=999\n", RUN_ID)

    def test_shell_metacharacters_in_selection_are_refused(self):
        for value in (CLASS + "; touch /tmp/unsafe", CLASS + "\nanything", "$(whoami)", "other.Test"):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                runner.launch_script(RUN_ID, [value])
        runner.validate_classes([CLASS + "#historyFlow"])

    def test_local_shell_claim_is_atomic_and_paths_are_quoted(self):
        # Exercise the generated shell on temporary local fixtures, substituting only
        # the remote directory and a stub executable. This never contacts a device.
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            directory = base / "job with ' quote"
            counter = base / "counter"
            executable = base / "am"
            executable.write_text('#!/bin/sh\nprintf "call\\n" >> "$FERROMC_TEST_COUNTER"\nprintf "test fixture\\n"\n')
            executable.chmod(0o700)
            env = {**os.environ, "PATH": str(base) + os.pathsep + os.environ["PATH"],
                   "FERROMC_TEST_COUNTER": str(counter)}
            with patch.object(runner, "remote_directory", return_value=str(directory)):
                script = runner.launch_script(RUN_ID, [CLASS])
            first = subprocess.run(["sh", "-c", script], env=env, capture_output=True, text=True, timeout=5)
            self.assertEqual(0, first.returncode, first.stderr)
            deadline = time.monotonic() + 5
            while not (directory / "exit-code").exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertEqual("0", (directory / "exit-code").read_text().strip())
            second = subprocess.run(["sh", "-c", script], env=env, capture_output=True, text=True, timeout=5)
            self.assertEqual(73, second.returncode)
            self.assertEqual(["call"], counter.read_text().splitlines())
            self.assertEqual(RUN_ID, (directory / "run_id").read_text().strip())

    def test_uncertain_launch_is_persisted_and_never_repeated(self):
        class LostAcknowledgement:
            calls = 0
            def shell(self, script, retry):
                self.calls += 1
                self.retry = retry
                raise runner.Pending("USB lost after possible acceptance")
        device = LostAcknowledgement()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "run.json"
            metadata = {"run_id": RUN_ID, "classes": [CLASS], "launch_attempted": False}
            runner.launch_once(device, metadata, path)
            self.assertTrue(json.loads(path.read_text())["launch_attempted"])
            self.assertEqual("acknowledgement_unknown", metadata["launch_state"])
            with self.assertRaisesRegex(RuntimeError, "already attempted"):
                runner.launch_once(device, metadata, path)
            self.assertEqual(1, device.calls)
            self.assertFalse(device.retry)

    def test_resume_requires_same_serial_and_original_claimed_job(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)
            data = {"run_id": RUN_ID, "serial": "S24", "remote_directory": runner.remote_directory(RUN_ID),
                    "runner": runner.RUNNER, "classes": [CLASS], "expected_tests": 1, "launch_attempted": True}
            (path / "run.json").write_text(json.dumps(data))
            self.assertEqual(data, runner.load_resume(path, "S24"))
            with self.assertRaisesRegex(RuntimeError, "serial"):
                runner.load_resume(path, "OTHER")
            data["launch_attempted"] = False
            (path / "run.json").write_text(json.dumps(data))
            with self.assertRaisesRegex(RuntimeError, "never launched"):
                runner.load_resume(path, "S24")


if __name__ == "__main__":
    unittest.main()
