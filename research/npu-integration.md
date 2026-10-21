# S24 Ultra NPU integration

The app targets Snapdragon 8 Gen 3 / SM8650, QNN SoC ID 57 and Hexagon HTP V75. It uses official Maven artifact `com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0`, whose POM pins `com.qualcomm.qti:qnn-runtime:2.42.0`. A separate QAIRT SDK installation is unnecessary. Do not add a second `onnxruntime-android` dependency because it duplicates classes.

## Packaging and provenance

`tools/setup_qnn_runtime.py` checks official artifact SHA-1 and pinned SHA-256, verifies the V75 libraries, and preserves the actual runtime licenses and notices. QNN's AAR contains an AI Stack License and `NOTICE.txt`; its POM links a different AI Hub Model license. The packaged runtime files are the retained source of terms. See [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md).

Only ARM64 is packaged. `jniLibs.useLegacyPackaging = true` enables extracted native libraries so QNN can find its stub/skeleton files. Optional `uses-native-library` declarations cover device-provided `libcdsprpc.so` and `libadsprpc.so`. The merged telemetry initializer and network permissions are removed, and ORT telemetry is disabled. No root, vendor partition change or OEM unlocking is needed.

The retained native set is `libonnxruntime.so`, `libonnxruntime4j_jni.so`, `libQnnHtp.so`, `libQnnHtpPrepare.so`, `libQnnSystem.so`, `libQnnHtpV75Stub.so` and `libQnnHtpV75Skel.so`. `tools/setup_qnn_native_audit.py` checks ELF dependencies. HTP Prepare is required because the device prepares ONNX graphs. Static dependency inspection alone does not establish dynamic loading or actual device execution.

## Two strict model sessions

| Asset | Parameters | Input | Output |
|---|---:|---|---|
| `seed_model.onnx` | 82,432 | `chord_context: float32[16,256]` | `pitch_logits: float32[16,256]` |
| `sound_model.onnx` | 10,432 | `sound_context: float32[16,32]` | `sound_logits: float32[16,64]` |

Both graphs use static U8/U8 QDQ quantization. CPU code prepares tensors, samples distributions and synthesizes audio; it does not execute either learned graph. There is no phone backpropagation.

For **each** session, `NpuEngine` explicitly selects `libQnnHtp.so`, sets `session.disable_cpu_ep_fallback=1` and `offload_graph_io_quantization=0`, runs a warm-up and inspects its actual ORT dispatch profile. Readiness requires at least one QNN node event and zero non-QNN graph-node events in both profiles. Provider registration or a pitch-only success cannot satisfy the sound-model check. Missing libraries, unsupported graphs and incompatible firmware make generation unavailable.

All model calls run outside the UI/audio thread. Inference and close are synchronized. Tensor shapes and finite outputs are checked. Batch 16 spreads RPC overhead across candidates; it is not evidence of a latency, energy or CPU-comparison advantage.

## Evidence

Debug builds write private diagnostics with both model hashes, runtime versions, strict options, separate invocation counts, ORT dispatch profiles and QNN detailed profiles. `tools/verify_device.py` binds acceptance to a fresh run and the exact APK/model assets. `tools/run_device_ui.py` separately checks real generation and persistence through the UI. Both support collecting an already-started detached run after USB reconnect without starting duplicate tests. Raw evidence stays in ignored `artifacts/`.

Use the commands in [reproduction.md](reproduction.md). Current accepted scope is recorded in [validation.md](validation.md). A previous single-model device pass does not verify the two-model APK, and emulator UI passes do not demonstrate HTP execution.

## References

- [Microsoft Android QNN 1.29.0 POM](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/1.29.0/onnxruntime-android-qnn-1.29.0.pom)
- [Qualcomm QNN runtime 2.42.0 POM](https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/2.42.0/qnn-runtime-2.42.0.pom)
- [ORT QNN provider options and profiling](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)
- [Ferro DSP reference](https://github.com/studyreadbook4ever/Ferro/blob/ea8a1451ebca207e15cb25361a6a053f9712ac48/src/dsp.cpp)
