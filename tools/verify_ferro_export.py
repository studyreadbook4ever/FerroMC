#!/usr/bin/env python3
"""Verify actual production exports with a pinned, unmodified Ferro loader and renderer.

Requires JDK 17+, g++, pkg-config, Qt6Core and libsndfile development packages.
Network downloads are limited to the reference Git repository and org.json jar.
Default mode checks generated fixtures. To check a real saved Android document:
  python3 tools/verify_ferro_export.py --expected record.json --project saved.ferro \
      --report artifacts/ferro-device-export-validation.json
The verifier also writes PROJECT.edited.ferro beside each checked project. No push occurs.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
REFERENCE = "ea8a1451ebca207e15cb25361a6a053f9712ac48"
REPOSITORY = "https://github.com/studyreadbook4ever/Ferro.git"
SOURCE = ROOT / ".work/ferro-reference"
WORK = ROOT / ".work/ferro-tests"
JSON_URL = "https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar"
JSON_SHA256 = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"


def run(command, **kwargs):
    return subprocess.run(command, check=True, cwd=ROOT, text=True, **kwargs)


def output(command):
    return run(command, capture_output=True).stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected", type=Path, help="Original NoteRecord JSON for an actual exported document")
    parser.add_argument("--project", type=Path, action="append", help="Existing .ferro file to verify; repeatable")
    parser.add_argument("--report", type=Path, help="Report JSON destination")
    parser.add_argument("--render-dir", type=Path, help="Optional directory for actual Ferro stereo float WAV test renders")
    args = parser.parse_args()
    if bool(args.expected) != bool(args.project):
        parser.error("--expected and --project must be supplied together")
    if args.expected and (not args.expected.is_file() or any(not path.is_file() for path in args.project)):
        parser.error("Expected record and project paths must be existing files")
    required = ("git", "g++", "pkg-config") if args.expected else ("git", "javac", "java", "g++", "pkg-config")
    for program in required:
        if shutil.which(program) is None:
            raise SystemExit("Missing build prerequisite: " + program)
    output(["pkg-config", "--modversion", "Qt6Core", "sndfile"])
    WORK.mkdir(parents=True, exist_ok=True)
    if not SOURCE.exists():
        run(["git", "clone", "--filter=blob:none", "--no-checkout", REPOSITORY, str(SOURCE)])
    else:
        if output(["git", "-C", str(SOURCE), "status", "--porcelain"]):
            raise SystemExit("The reference checkout has local changes; leaving it untouched.")
        actual_origin = output(["git", "-C", str(SOURCE), "remote", "get-url", "origin"])
        if actual_origin.rstrip("/") != REPOSITORY:
            raise SystemExit("The reference checkout has an unexpected origin; leaving it untouched.")
    exists = subprocess.run(["git", "-C", str(SOURCE), "cat-file", "-e", REFERENCE + "^{commit}"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if exists.returncode != 0:
        run(["git", "-C", str(SOURCE), "fetch", "origin", REFERENCE])
    run(["git", "-C", str(SOURCE), "checkout", "--detach", REFERENCE])
    if output(["git", "-C", str(SOURCE), "rev-parse", "HEAD"]) != REFERENCE:
        raise SystemExit("Unexpected reference revision.")

    if args.expected:
        projects = [path.resolve() for path in args.project]
    else:
        dependency = WORK / "deps/json.jar"
        dependency.parent.mkdir(parents=True, exist_ok=True)
        if not dependency.exists():
            with urllib.request.urlopen(JSON_URL, timeout=60) as response:
                dependency.write_bytes(response.read())
        if hashlib.sha256(dependency.read_bytes()).hexdigest() != JSON_SHA256:
            raise SystemExit("org.json dependency checksum mismatch.")
        classes = WORK / "exporter-classes"
        classes.mkdir(exist_ok=True)
        java_root = ROOT / "app/src/main/java/dev/ferro/seed"
        run(["javac", "--release", "17", "-cp", str(dependency), "-d", str(classes),
             str(java_root / "NoteRecord.java"), str(java_root / "FerroExporter.java"),
             str(ROOT / "tools/FerroFixtureMain.java")])
        fixtures = ROOT / "artifacts/ferro-fixtures"
        run(["java", "-cp", os.pathsep.join((str(classes), str(dependency))),
             "FerroFixtureMain", str(fixtures)])
        projects = [fixtures / f"export-{bpm}.ferro" for bpm in (40, 120, 300)]

    flags = shlex.split(output(["pkg-config", "--cflags", "Qt6Core", "sndfile"]))
    libraries = shlex.split(output(["pkg-config", "--libs", "Qt6Core", "sndfile"]))
    binary = WORK / "verify-loader"
    run(["g++", "-std=c++17", "-O2", "-fPIC", *flags, "-I" + str(SOURCE / "src"),
         str(ROOT / "tools/ferro_verify_loader.cpp"), str(SOURCE / "src/project.cpp"),
         str(SOURCE / "src/dsp.cpp"), "-o", str(binary), *libraries])
    expected_arguments = ["--expected", str(args.expected.resolve())] if args.expected else []
    if args.render_dir:
        expected_arguments += ["--render-dir", str(args.render_dir.resolve())]
    checked = run([str(binary), *expected_arguments, *[str(path) for path in projects]], capture_output=True)
    report = json.loads(checked.stdout)
    if report.get("ok") is not True or len(report.get("results", [])) != len(projects):
        raise SystemExit("The reference verifier returned an incomplete result.")
    if args.expected:
        report["expected_record_sha256"] = hashlib.sha256(args.expected.read_bytes()).hexdigest()
        report["scope"] = "Existing .ferro matches supplied NoteRecord and is editable with upstream Ferro; NPU provenance requires separate device evidence."
    else:
        report["fixture_generator"] = "production NoteRecord.java + FerroExporter.java"
        report["scope"] = "Desktop editable-project compatibility; no Android or NPU execution claimed."
    for result, project in zip(report["results"], projects):
        result["source_sha256"] = hashlib.sha256(project.read_bytes()).hexdigest()
    report["reference_sources_unmodified"] = not bool(output(
        ["git", "-C", str(SOURCE), "status", "--porcelain"]))
    destination = args.report or ROOT / ("artifacts/ferro-record-export-validation.json" if args.expected
                                        else "artifacts/ferro-loader-validation.json")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    print("Evidence:", destination)


if __name__ == "__main__":
    main()
