#!/usr/bin/env python3
"""Inspect official AAR ELF dependency metadata for the SM8650-only package."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / ".work/qnn"
KEEP = {
    "libonnxruntime.so", "libonnxruntime4j_jni.so", "libQnnHtp.so",
    "libQnnHtpPrepare.so", "libQnnSystem.so", "libQnnHtpV75Stub.so",
    "libQnnHtpV75Skel.so",
}


def main():
    target = WORK / "elf"
    target.mkdir(parents=True, exist_ok=True)
    libraries = []
    for archive in sorted(WORK.glob("*.aar")):
        with zipfile.ZipFile(archive) as package:
            for entry in package.infolist():
                if not entry.filename.endswith(".so"):
                    continue
                path = target / Path(entry.filename).name
                data = package.read(entry)
                path.write_bytes(data)
                dynamic = subprocess.run(["readelf", "-d", str(path)], check=True,
                                         capture_output=True, text=True).stdout
                needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
                libraries.append(dict(name=path.name, source=archive.name,
                                      sha256=hashlib.sha256(data).hexdigest(),
                                      bytes=entry.file_size, aar_compressed_bytes=entry.compress_size,
                                      dt_needed=needed, keep_for_sm8650=path.name in KEEP))
    actual = {item["name"] for item in libraries}
    if not KEEP.issubset(actual):
        raise RuntimeError("Required native libraries missing: " + str(KEEP - actual))
    removed = actual - KEEP
    for item in libraries:
        if item["keep_for_sm8650"] and removed.intersection(item["dt_needed"]):
            raise RuntimeError("Kept library depends on a proposed removal: " + item["name"])
    report = dict(target="Samsung S24 Ultra / SM8650 / Hexagon V75", libraries=libraries,
                  keep=sorted(KEEP), remove=sorted(removed),
                  uncompressed_bytes_removed=sum(x["bytes"] for x in libraries if not x["keep_for_sm8650"]),
                  aar_compressed_bytes_removed=sum(x["aar_compressed_bytes"] for x in libraries if not x["keep_for_sm8650"]),
                  device_execution_verified=False,
                  qualification="DT_NEEDED does not enumerate dlopen. QNN architecture selection is explicitly V75; keep the HTP prepare library for on-device graph creation.")
    output = ROOT / "artifacts/npu-native-dependencies.json"
    output.write_text(json.dumps(report, indent=2) + "\n")
    print("SM8650 native set:", ", ".join(sorted(KEEP)))
    print("Proposed removals (uncompressed bytes):", report["uncompressed_bytes_removed"])
    print("Proposed removals (AAR compressed bytes):", report["aar_compressed_bytes_removed"])
    print("No retained DT_NEEDED points to a removed library; real-device validation remains required.")


if __name__ == "__main__":
    main()
