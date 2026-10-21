# Third-party notices

The root [Unlicense](LICENSE) applies to FerroMC's original source, documentation and model weights trained for this project. It does not replace the licenses of dependencies, build tools or third-party notices included here.

## Runtime dependencies

| Component | Version / origin | License and retained notices |
|---|---|---|
| ONNX Runtime Android QNN | `com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0` | [MIT license](app/src/main/assets/licenses/onnxruntime-LICENSE.txt) and [upstream third-party notices](app/src/main/assets/licenses/onnxruntime-ThirdPartyNotices.txt). |
| Qualcomm QNN Runtime | Transitive `com.qualcomm.qti:qnn-runtime:2.42.0` | Proprietary [AI Stack License](app/src/main/assets/licenses/qnn-LICENSE.pdf) and complete [QNN third-party notices](app/src/main/assets/licenses/qnn-NOTICE.txt), extracted unchanged from the actual runtime AAR. |

QNN is not public-domain or Unlicense software. Its packaged AI Stack License §1 permits object-code distribution incorporated in an application, subject to its terms, and excludes standalone redistribution. Its proprietary and third-party notices must be retained. Read the full terms before distributing a binary containing it.

The source repository does not vendor QNN AARs, shared libraries or a Qualcomm SDK. Gradle resolves the pinned Maven dependency. The retained ARM64 runtime libraries in a local APK are ONNX Runtime's two libraries plus QNN HTP, HTP Prepare, System, V75 Stub and V75 Skel. Device FastRPC libraries come from Android's vendor image and are not copied into the project.

**Provenance detail:** the [QNN 2.42.0 POM](https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/2.42.0/qnn-runtime-2.42.0.pom) links an AI Hub Model license. The [actual runtime AAR](https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/2.42.0/qnn-runtime-2.42.0.aar) contains a different `LICENSE.pdf`, titled *AI Stack License*, plus `NOTICE.txt`. This project preserves those packaged files. `tools/setup_qnn_runtime.py` verifies the AAR and notice hashes; it does not substitute the POM's model license for the runtime terms.

| Packaged file | SHA-256 |
|---|---|
| `qnn-runtime-2.42.0.aar` | `ecb69bd6a7b1be4717ef10805199fb49ec6b60ca3f481d3068ed9605fba91d5b` |
| `LICENSE.pdf` | `ec1dccfdcba5c6e64126e84199b8362bf4999107bfa567ebe831dbb4c461692b` |
| `NOTICE.txt` | `c8a22fa8b9ed3e9266c4a3366965474cef5cb147ee2a1f33e488bbb24a071c09` |

## Ferro DSP source

The Java Saw renderer ports the algorithms in [Ferro's `src/dsp.cpp`](https://github.com/studyreadbook4ever/Ferro/blob/ea8a1451ebca207e15cb25361a6a053f9712ac48/src/dsp.cpp), including waveform generation, envelope, filters and limiting. The export contract and desktop verification use the same pinned commit `ea8a1451ebca207e15cb25361a6a053f9712ac48`. Ferro publishes this source under the [Unlicense](https://github.com/studyreadbook4ever/Ferro/blob/ea8a1451ebca207e15cb25361a6a053f9712ac48/LICENSE). The Java adaptation changes language, output plumbing and preview volume handling; the upstream checkout used by tests stays outside the repository.

## Build, test and training tools

The checked-in Gradle wrapper and scripts retain their upstream copyright headers. Gradle 8.13 is Apache-2.0; its complete license is embedded in `gradle/wrapper/gradle-wrapper.jar` at `META-INF/LICENSE` and available [upstream](https://www.apache.org/licenses/LICENSE-2.0). The Android Gradle plugin and AndroidX test components are resolved by the build under their own licenses; they are not re-licensed by this repository.

JUnit 4.13.2, Hamcrest and JSON-java 20240303 are host/test dependencies, not application implementation dependencies. Their original package licenses apply. The build does not package the host JSON-java jar into the app; Android supplies `org.json` at runtime.

PyTorch, NumPy, ONNX and the desktop ONNX Runtime are independently installed training/validation tools. Their distributions and licenses are not vendored here. Both shipped ONNX models were trained from random initialization on this project's synthetic data; they contain no imported pretrained weights.

The desktop Ferro harness links locally installed Qt 6 Core and libsndfile. Their respective licenses apply to those installed libraries; the harness binary and upstream clone are excluded from source publication. No third-party runtime or tool is made Unlicense merely by building or using FerroMC.
