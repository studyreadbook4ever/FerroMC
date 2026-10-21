#!/usr/bin/env python3
"""Run installed S24 UI tests in one detached phone job; resume polling, never relaunch."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.ferro.seed"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
CLASS_PATTERN = re.compile(r"dev\.ferro\.seed\.[A-Za-z][A-Za-z0-9_]*(?:#[A-Za-z][A-Za-z0-9_]*)?")
TRANSIENT = ("offline", "unauthorized", "no devices", "closed", "cannot connect",
             "connection reset", "transport", "device still authorizing")


class Pending(RuntimeError):
    """The remote job may still run. A new launch is not authorized by this result."""


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def save_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n")
    temporary.replace(path)


def remote_directory(run_id):
    require(re.fullmatch(r"[0-9a-f]{32}", run_id) is not None, "Invalid run ID")
    return "/data/local/tmp/ferromc-ui-" + run_id


def validate_classes(classes):
    require(bool(classes), "Supply explicit --class arguments")
    require(len(set(classes)) == len(classes), "Duplicate test selection")
    for name in classes:
        require(CLASS_PATTERN.fullmatch(name) is not None, "Invalid test class or method: " + name)
    return classes


def launch_script(run_id, classes):
    """One atomic directory claim. Retrying this exact script cannot launch twice."""
    directory = remote_directory(run_id)
    validate_classes(classes)
    command = shlex.join(["am", "instrument", "-w", "-r", "-e", "class", ",".join(classes),
                          "-e", "sori_validation_run_id", run_id, RUNNER])
    job = "\n".join([
        "#!/system/bin/sh", "umask 077", "d=" + shlex.quote(directory),
        'printf "%s\\n" "$$" > "$d/pid"',
        'printf "%s\\n" ' + shlex.quote(run_id) + ' > "$d/started"',
        command + ' > "$d/instrumentation.txt" 2>&1',
        "result=$?", 'printf "%s\\n" "$result" > "$d/exit-code.tmp"',
        'mv "$d/exit-code.tmp" "$d/exit-code"', 'exit "$result"', ""])
    return "\n".join([
        "umask 077", "d=" + shlex.quote(directory),
        'if ! mkdir "$d"; then echo "RUN_ALREADY_CLAIMED" >&2; exit 73; fi',
        'printf "%s\\n" ' + shlex.quote(run_id) + ' > "$d/run_id"',
        'cat > "$d/job.sh" <<\'FERROMC_DETACHED_JOB\'', job, "FERROMC_DETACHED_JOB",
        'nohup sh "$d/job.sh" </dev/null >"$d/launcher.log" 2>&1 &',
        'printf "%s\\n" "$!" > "$d/launcher.pid"',
        'printf "LAUNCH_ACCEPTED %s\\n" ' + shlex.quote(run_id), ""])


def poll_script(run_id):
    directory = remote_directory(run_id)
    return "\n".join([
        "d=" + shlex.quote(directory),
        'if [ ! -d "$d" ]; then printf "state=missing\\n"; exit 0; fi',
        'if [ ! -f "$d/run_id" ]; then printf "state=claim_pending\\n"; exit 0; fi',
        'printf "run_id="; cat "$d/run_id" 2>/dev/null',
        'if [ -f "$d/exit-code" ]; then printf "state=finished\\nexit_code="; cat "$d/exit-code"; exit 0; fi',
        'if [ ! -f "$d/pid" ]; then printf "state=starting\\n"; exit 0; fi',
        'p=$(cat "$d/pid")',
        'case "$p" in ""|*[!0-9]*) printf "state=invalid_pid\\n"; exit 0;; esac',
        'printf "pid=%s\\n" "$p"',
        'if kill -0 "$p" 2>/dev/null; then printf "state=running\\n"; else printf "state=lost\\n"; fi', ""])


def parse_poll(text, run_id):
    values = {}
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        require(separator and key not in values, "Malformed remote job state")
        values[key] = value
    require(values.get("state") in {"missing", "claim_pending", "starting", "running", "finished", "lost", "invalid_pid"},
            "Unrecognized remote job state")
    if values["state"] not in ("missing", "claim_pending"):
        require(values.get("run_id") == run_id, "Remote job belongs to another run")
    if values["state"] == "finished":
        require(re.fullmatch(r"[0-9]{1,3}", values.get("exit_code", "")) is not None,
                "Invalid remote exit code")
        values["exit_code"] = int(values["exit_code"])
        require(values["exit_code"] <= 255, "Invalid remote exit code")
    return values


def validate_instrumentation(text, exit_code, classes, expected_tests):
    require(exit_code == 0, "Remote am instrument exited unsuccessfully")
    require(not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT: shortMsg=", text),
            "Instrumentation reported a failure")
    require(re.findall(r"(?m)^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", text) == ["-1"],
            "Missing unique successful instrumentation completion")
    totals = re.findall(r"(?m)^OK \((\d+) tests?\)\s*$", text)
    require(totals == [str(expected_tests)], "Passing test count differs from requested count")
    blocks, current = [], {}
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator:
                current[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(":", 1)[1])
            require(code in (0, 1), "Failed, ignored or skipped test in instrumentation")
            if code == 0:
                require(current.get("numtests") == str(expected_tests), "Instrumentation suite count mismatch")
                blocks.append((current.get("class", ""), current.get("test", "")))
            current = {}
    require(len(blocks) == expected_tests and len(set(blocks)) == expected_tests,
            "Missing or duplicate successful test records")
    for class_name, method in blocks:
        require(bool(method) and any(selection == class_name or selection == class_name + "#" + method
                                     for selection in classes), "Unexpected test executed")
    for selection in classes:
        require(any(selection == cls or selection == cls + "#" + method for cls, method in blocks),
                "A selected test class/method did not run")
    return [{"class": cls, "method": method} for cls, method in blocks]


class Device:
    def __init__(self, adb, serial, output, timeout):
        self.adb, self.serial, self.output = adb, serial, output
        self.deadline = time.monotonic() + timeout

    def call(self, args, retry=True):
        while True:
            remaining = self.deadline - time.monotonic()
            if remaining <= 0:
                raise Pending("Polling deadline reached; the same remote job may still be running")
            command = [self.adb, "-s", self.serial, *args]
            try:
                result = subprocess.run(command, capture_output=True, timeout=min(20, remaining))
                message = (result.stdout + result.stderr).decode("utf-8", errors="replace")
                row = {"args": args, "returncode": result.returncode,
                       "stderr": result.stderr.decode("utf-8", errors="replace")}
            except subprocess.TimeoutExpired:
                result = None
                message = "ADB command timed out"
                row = {"args": args, "timeout": True}
            with (self.output / "adb-commands.jsonl").open("a") as log:
                log.write(json.dumps({"at_epoch_ms": int(time.time() * 1000), **row}) + "\n")
            if result is not None and result.returncode == 0:
                return result.stdout
            if not retry:
                raise Pending("Launch acknowledgement unavailable; poll this run without relaunching: " + message)
            transient = (any(x in message.lower() for x in TRANSIENT)
                         or re.search(r"device .*not found", message.lower()) is not None)
            if result is not None and not transient:
                raise RuntimeError("ADB read failed: " + message.strip())
            time.sleep(min(2, max(0, self.deadline - time.monotonic())))

    def shell(self, script, retry=True):
        # ADB shell joins its arguments; quote the entire sh program explicitly.
        return self.call(["shell", "sh -c " + shlex.quote(script)], retry=retry)

    def physical_identity(self):
        keys = ("ro.product.manufacturer", "ro.product.model", "ro.product.cpu.abi",
                "ro.kernel.qemu", "ro.soc.model")
        properties = {key: self.call(["shell", "getprop", key]).decode().strip() for key in keys}
        require(properties["ro.product.manufacturer"].lower() == "samsung", "Expected Samsung device")
        require(properties["ro.product.model"].startswith("SM-S928"), "Expected physical S24 Ultra")
        require(properties["ro.product.cpu.abi"] == "arm64-v8a", "Expected physical ARM64 S24 Ultra")
        require(properties["ro.kernel.qemu"] != "1", "Emulator refused")
        require(not properties["ro.soc.model"] or "SM8650" in properties["ro.soc.model"], "Unexpected SoC")
        return properties


def launch_once(device, metadata, metadata_path):
    require(not metadata.get("launch_attempted"), "This run has already attempted launch; resume polling only")
    metadata["launch_attempted"] = True
    metadata["launch_state"] = "acknowledgement_unknown"
    save_json(metadata_path, metadata)  # Persist BEFORE sending a possibly accepted command.
    try:
        output = device.shell(launch_script(metadata["run_id"], metadata["classes"]), retry=False)
    except Pending as error:
        metadata["launch_warning"] = str(error)
    else:
        require(output.decode().strip() == "LAUNCH_ACCEPTED " + metadata["run_id"],
                "Unexpected launch acknowledgement; resume this run, never relaunch")
        metadata["launch_state"] = "accepted"
    save_json(metadata_path, metadata)


def load_resume(path, serial):
    data = json.loads((path / "run.json").read_text())
    require(data.get("serial") == serial, "Resume serial differs from original device")
    require(data.get("remote_directory") == remote_directory(data.get("run_id", "")), "Invalid remote directory")
    require(data.get("runner") == RUNNER, "Unexpected instrumentation runner")
    validate_classes(data.get("classes"))
    require(type(data.get("expected_tests")) is int and data["expected_tests"] > 0, "Invalid test count")
    require(data.get("launch_attempted") is True, "This run was never launched; resume cannot launch it")
    return data


def wait_and_collect(device, metadata, interval):
    run_id, directory = metadata["run_id"], metadata["remote_directory"]
    previous_state = None
    while True:
        state = parse_poll(device.shell(poll_script(run_id)).decode(), run_id)
        if state["state"] != previous_state:
            print("Remote job: " + state["state"], flush=True)
            previous_state = state["state"]
        if state["state"] in ("finished", "lost", "invalid_pid"):
            break
        time.sleep(min(interval, max(0, device.deadline - time.monotonic())))
    # These files belong exclusively to this UUID. Never clear app data or delete remote files.
    for filename in ("run_id", "pid", "launcher.pid", "launcher.log", "instrumentation.txt", "exit-code"):
        try:
            content = device.call(["exec-out", "cat", directory + "/" + filename])
        except RuntimeError as error:
            if isinstance(error, Pending):
                raise
            if state["state"] == "finished" and filename in ("run_id", "pid", "instrumentation.txt", "exit-code"):
                raise
            continue
        (device.output / filename).write_bytes(content)
    require(state["state"] == "finished", "Remote wrapper exited without an atomic exit-code; no automatic rerun")
    require((device.output / "run_id").read_text().strip() == run_id, "Collected evidence belongs to another run")
    require((device.output / "exit-code").read_text().strip() == str(state["exit_code"]), "Exit-code changed")
    require(re.fullmatch(r"[1-9][0-9]*", (device.output / "pid").read_text().strip()) is not None, "Invalid job PID")
    text = (device.output / "instrumentation.txt").read_text(errors="replace")
    tests = validate_instrumentation(text, state["exit_code"], metadata["classes"], metadata["expected_tests"])
    return {"remote_exit_code": state["exit_code"], "successful_tests": tests,
            "scope": "Selected installed test classes and detached-job evidence; inspect their app-specific reports separately."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--class", dest="classes", action="append", help="Explicit installed test class; repeat to select multiple")
    parser.add_argument("--expected-tests", type=int, help="Exact expected test method count; required for a new run")
    parser.add_argument("--resume-run", type=Path, help="Existing host run directory; never launches a new phone job")
    parser.add_argument("--timeout", type=float, default=600, help="Host polling deadline, not a remote test timeout")
    parser.add_argument("--poll-seconds", type=float, default=2)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args(argv)
    require(re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial) is not None and not args.serial.startswith("emulator-"),
            "An exact physical S24 serial is required")
    require(args.timeout > 0 and 0 < args.poll_seconds <= 60, "Invalid polling duration")
    if args.resume_run:
        require(not args.classes and args.expected_tests is None, "Resume uses the original class selection and count")
        output = args.resume_run.resolve()
        metadata = load_resume(output, args.serial)
    else:
        validate_classes(args.classes)
        require(args.expected_tests is not None and args.expected_tests > 0, "--expected-tests is required")
        run_id = uuid.uuid4().hex
        output = ROOT / "artifacts/device" / args.serial / ("ui-detached-" + run_id)
        output.mkdir(parents=True, exist_ok=False)
        metadata = {"schema_version": 1, "run_id": run_id, "serial": args.serial,
                    "remote_directory": remote_directory(run_id), "runner": RUNNER,
                    "classes": args.classes, "expected_tests": args.expected_tests,
                    "launch_attempted": False, "created_at_epoch_ms": int(time.time() * 1000)}
        save_json(output / "run.json", metadata)
    print("Host evidence: " + str(output), flush=True)
    device = Device(args.adb, args.serial, output, args.timeout)
    summary = {"run_id": metadata["run_id"], "serial": args.serial, "status": "running",
               "remote_directory": metadata["remote_directory"], "shutdown_attempted": False}
    try:
        summary["device_properties"] = device.physical_identity()
        if not args.resume_run:
            launch_once(device, metadata, output / "run.json")
        summary["validation"] = wait_and_collect(device, metadata, args.poll_seconds)
        summary["status"] = "passed"
        result = 0
    except (Pending, KeyboardInterrupt) as error:
        summary["status"] = "pending_resume"
        summary["error"] = str(error) or "Host polling interrupted; remote job was not stopped"
        result = 2
    except Exception as error:
        summary["status"] = "failed"
        summary["error"] = str(error)
        result = 1
    summary["updated_at_epoch_ms"] = int(time.time() * 1000)
    save_json(output / "summary.json", summary)
    print(summary["status"] + (": " + summary["error"] if "error" in summary else ""), flush=True)
    if summary["status"] != "passed" and metadata.get("launch_attempted"):
        print("Poll the SAME job: " + shlex.join([sys.executable, str(Path(__file__).resolve()), "--serial", args.serial,
              "--resume-run", str(output), "--timeout", str(args.timeout)]), flush=True)
    return result


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, OSError, ValueError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
