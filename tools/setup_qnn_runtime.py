#!/usr/bin/env python3
"""Fetch and inspect the exact official Android QNN artifacts used by Gradle.

No SDK installer, root access, third-party binary mirror, or device modification.
Artifacts stay in .work; the build resolves the same pinned Maven coordinates.
"""
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / ".work" / "qnn"
ARTIFACTS = (
    ("com.microsoft.onnxruntime", "onnxruntime-android-qnn", "1.29.0",
     "cc4cfd72a47703aee65d8e6ce7448fa7488e3fff6556f9d6d354ec1687347cfd"),
    ("com.qualcomm.qti", "qnn-runtime", "2.42.0",
     "ecb69bd6a7b1be4717ef10805199fb49ec6b60ca3f481d3068ed9605fba91d5b"),
)
LICENSES = (
    ("onnxruntime-LICENSE.txt",
     "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.29.0/LICENSE",
     "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c"),
    ("onnxruntime-ThirdPartyNotices.txt",
     "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.29.0/ThirdPartyNotices.txt",
     "53d3fa5821ac016ac24dd35775c996efec86e2ae0841e9a3a5e146c0ae916845"),
)
PACKAGED_LICENSES = (
    ("LICENSE.pdf", "qnn-LICENSE.pdf",
     "ec1dccfdcba5c6e64126e84199b8362bf4999107bfa567ebe831dbb4c461692b"),
    ("NOTICE.txt", "qnn-NOTICE.txt",
     "c8a22fa8b9ed3e9266c4a3366965474cef5cb147ee2a1f33e488bbb24a071c09"),
)


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    records = []
    for group, name, version, pinned_sha256 in ARTIFACTS:
        stem = f"{name}-{version}"
        base = ("https://repo.maven.apache.org/maven2/"
                f"{group.replace('.', '/')}/{name}/{version}/{stem}")
        for ext in ("pom", "aar", "aar.sha1"):
            path = DEST / f"{stem}.{ext}"
            if not path.exists():
                with urllib.request.urlopen(base + "." + ext, timeout=120) as response:
                    path.write_bytes(response.read())
        archive = DEST / f"{stem}.aar"
        data = archive.read_bytes()
        sha1 = hashlib.sha1(data).hexdigest()
        sha256 = hashlib.sha256(data).hexdigest()
        if sha1 != (DEST / f"{stem}.aar.sha1").read_text().strip():
            raise RuntimeError(f"Published SHA-1 mismatch: {archive}")
        if sha256 != pinned_sha256:
            raise RuntimeError(f"Pinned SHA-256 mismatch: {archive}")
        with zipfile.ZipFile(archive) as package:
            libraries = sorted(n for n in package.namelist() if n.endswith(".so"))
            if "classes.jar" in package.namelist():
                (DEST / f"{name}-classes.jar").write_bytes(package.read("classes.jar"))
        records.append(dict(coordinate=f"{group}:{name}:{version}",
                            url=base + ".aar", bytes=len(data), sha256=sha256,
                            published_sha1_verified=True, native_libraries=libraries))
    qnn_libraries = records[1]["native_libraries"]
    for name in ("libQnnHtp.so", "libQnnSystem.so", "libQnnHtpPrepare.so",
                 "libQnnHtpV75Stub.so", "libQnnHtpV75Skel.so"):
        if f"jni/arm64-v8a/{name}" not in qnn_libraries:
            raise RuntimeError("Required SM8650/V75 library missing: " + name)
    license_dir = ROOT / "app/src/main/assets/licenses"
    license_dir.mkdir(parents=True, exist_ok=True)
    notices = []
    for name, url, expected_hash in LICENSES:
        target = license_dir / name
        if not target.exists():
            with urllib.request.urlopen(url, timeout=120) as response:
                target.write_bytes(response.read())
        actual_hash = hashlib.sha256(target.read_bytes()).hexdigest()
        if actual_hash != expected_hash:
            raise RuntimeError("License provenance mismatch: " + name)
        notices.append(dict(file=str(target.relative_to(ROOT)), url=url, sha256=actual_hash))
    # The QNN POM links an AI Hub Model license, while the actual runtime AAR ships
    # an AI Stack license and its own third-party notices. Preserve the AAR's terms.
    qnn_archive = DEST / "qnn-runtime-2.42.0.aar"
    with zipfile.ZipFile(qnn_archive) as package:
        for member, name, expected_hash in PACKAGED_LICENSES:
            data = package.read(member)
            if hashlib.sha256(data).hexdigest() != expected_hash:
                raise RuntimeError("Packaged QNN license provenance mismatch: " + member)
            target = license_dir / name
            if not target.exists():
                target.write_bytes(data)
            if hashlib.sha256(target.read_bytes()).hexdigest() != expected_hash:
                raise RuntimeError("License asset differs from the QNN runtime AAR: " + name)
            notices.append(dict(file=str(target.relative_to(ROOT)),
                                archive="com.qualcomm.qti:qnn-runtime:2.42.0", member=member,
                                sha256=expected_hash))
    report = dict(artifacts=records, notices=notices, device_execution_verified=False,
                  licensing={
                      "onnxruntime": "https://github.com/microsoft/onnxruntime/blob/v1.29.0/LICENSE",
                      "qnn_pom_license": "https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf",
                      "qnn_packaged_license": "qnn-runtime-2.42.0.aar!/LICENSE.pdf (AI Stack License)",
                      "qnn_packaged_notice": "qnn-runtime-2.42.0.aar!/NOTICE.txt",
                      "qnn_license_source": "Actual runtime AAR; POM license link describes a different AI Hub Model license.",
                  })
    output = ROOT / "artifacts" / "npu-runtime-provenance.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Verified {len(records)} official Maven artifacts; V75 libraries present.")
    print(f"Evidence: {output.relative_to(ROOT)}")
    print("This is package validation, not evidence of phone/NPU execution.")


if __name__ == "__main__":
    main()
