# Reproducing FerroMC

Run commands from the repository root. Generated reports, device logs, recordings and screenshots stay in ignored `artifacts/`; they can contain device identifiers or personal records. Publish only reviewed summaries such as [validation.md](validation.md).

## Download and update

Download [FerroMC v0.1.1 APK](https://github.com/studyreadbook4ever/FerroMC/releases/latest/download/FerroMC.apk) for ARM64 Galaxy S24 Ultra. The GitHub Release asset `FerroMC.apk` is the **debug build**, version name `0.1.1` / version code `2`. It is signed with the same existing development key as the earlier installed build, allowing an update that preserves application data. The signing key is excluded from Git. A local rebuild uses that developer's own debug key unless the existing signing key is available; it may therefore be unable to update the published APK.

APK binaries are distributed through GitHub Releases and excluded from the source Git history. The packaged ONNX Runtime and Qualcomm QNN licenses/notices remain in the APK. Source and original model licensing are described in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and [LICENSE](../LICENSE).

For an authorized USB update, select the exact device as described below and install the downloaded APK with `adb -s "$FERROMC_DEVICE" install -r -t <path-to-FerroMC.apk>`. Preserve the existing app and records if a signature check fails. The version and signing certificate must be checked on the APK actually distributed and installed; prior-build test results do not establish the new artifact's validation.

## Build

Use JDK 17, Android SDK platform 35, platform tools and `readelf` from binutils. Set `ANDROID_HOME`, or put `sdk.dir` in the ignored `local.properties`. The checked-in wrapper uses Gradle 8.13. The application ID is `dev.ferro.seed`; the APK targets `arm64-v8a`, SM8650 and HTP V75.

```sh
mkdir -p artifacts
python3 tools/setup_qnn_runtime.py
python3 tools/setup_qnn_native_audit.py
python3 -m unittest discover -s tools/tests
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

The runtime scripts verify official pinned Maven artifacts and preserve their exact licenses. They write provenance/dependency reports under `artifacts/` and download archives only to `.work/qnn`. Gradle resolves the same dependencies. Outputs are `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. Host tests do not prove HTP execution.

The downloadable `FerroMC.apk` is the checked `app-debug.apk` copied under the release asset name. Record its SHA-256 and signing certificate, and test that exact APK before publication.

## Train and validate both models

Install `uv` to use the environment commands below. The recorded environment is Python 3.12.13, PyTorch 2.14.1+cpu, NumPy 2.5.4, ONNX 1.23.2 and ONNX Runtime 1.31.0.

```sh
uv venv --python 3.12.13 .venv
uv pip install --python .venv/bin/python torch==2.14.1+cpu --index-url https://download.pytorch.org/whl/cpu
uv pip install --python .venv/bin/python numpy==2.5.4 onnx==1.23.2 onnxruntime==1.31.0
mkdir -p artifacts
.venv/bin/python tools/train_model.py --epochs 36 --train-contexts 65536 --validation-contexts 8192 --batch-size 1024 --threads 4 --seed 20261021 > artifacts/model_training.log 2>&1
.venv/bin/python tools/model_validate.py > artifacts/model_validation.log 2>&1
.venv/bin/python tools/model_validate_app_sampler.py --samples 1024 > artifacts/model_app_sampler_validation.log 2>&1
.venv/bin/python tools/train_sound_model.py --epochs 48 --train-contexts 32768 --validation-contexts 4096 --seed 20261022 > artifacts/sound_model_training.log 2>&1
.venv/bin/python tools/model_validate_sound.py > artifacts/sound_model_validation.log 2>&1
```

Training replaces the model assets. Rebuild the APK afterward. Numerical checks use desktop CPU inference deliberately; phone generation still requires both graphs to execute on HTP. Library and floating-point differences can change regenerated hashes.

The pitch model has 82,432 parameters and learns a masked-pitch distribution using the historical three-partial reference timbre `[1, .25, .10]`. That training proxy is separate from the current Saw preview. The sound model has 10,432 parameters and uses an eight-harmonic, seven-time-point approximation of Ferro Saw. Its level-sensitive score can improve through lower velocity or shorter decay; improvement is not evidence of harmonic consonance or pleasantness. Both models use weak acoustic guidance and disjoint hashed pitch-context splits. No pretrained weights, music corpus, tonal labels or phone training are used.

Inspect `model_training_report.json`, `model_independent_validation.json`, `model_app_sampler_validation.json`, `sound_model_training_report.json` and `sound_model_independent_validation.json` in `artifacts/`. The checked-in [pitch metadata](../app/src/main/assets/model_info.json) and [sound metadata](../app/src/main/assets/sound_model_info.json) identify architecture, tensor contract and asset hashes.

## Physical S24 Ultra acceptance

Select the exact authorized device from `adb devices -l`; set `FERROMC_DEVICE` to that serial locally. Do not publish the serial or raw logs. Installations use `adb install -r -t` and preserve existing application data. A signing-key mismatch is an installation failure, not permission to uninstall or erase user records.

```sh
adb devices -l
python3 tools/verify_device.py --serial "$FERROMC_DEVICE" --timeout 300
python3 tools/run_device_ui.py --serial "$FERROMC_DEVICE" --class dev.ferro.seed.NpuUiSmokeTest --expected-tests 1 --timeout 600
```

Accept only a successful current run tied to the exact APK and both model hashes. Require CPU graph fallback disabled, graph I/O quantization offload disabled, HTP selected, nonempty QNN profiles, and zero non-QNN node dispatch for **each** graph. Missing runtime libraries, disconnections or unsupported graphs remain failures. The UI smoke must generate through the real button, preserve every note/velocity/sound value, replay without inference, survive Activity reopening, and remove only its own test records.

Both device runners launch uniquely identified detached instrumentation jobs. If USB disconnects, use the printed `--resume-run` command to collect the same job; do not launch a duplicate and combine results. For a backend run, use `python3 tools/verify_device.py --serial "$FERROMC_DEVICE" --resume-run <the-existing-run-directory> --timeout 600`; it collects the original run without installation or relaunch. Raw evidence remains under ignored `artifacts/device/`.

Run the separate UI/storage/audio checks on the installed test APK:

```sh
python3 tools/run_device_ui.py --serial "$FERROMC_DEVICE" --class dev.ferro.seed.UiFlowTest --expected-tests 1
python3 tools/run_device_ui.py --serial "$FERROMC_DEVICE" --class dev.ferro.seed.UiStateTest --expected-tests 1
python3 tools/run_device_ui.py --serial "$FERROMC_DEVICE" --class dev.ferro.seed.UiExportTest --expected-tests 1
python3 tools/run_device_ui.py --serial "$FERROMC_DEVICE" --class dev.ferro.seed.AudioPlaybackTest --expected-tests 2
```

The live-history UI tests require one or two free recent-history slots before adding fixtures, and otherwise fail before inserting them. They preserve existing records and clean up only unique test IDs/documents. HistoryRetentionTest exercises the 100-record boundary in a separate cache file, without modifying user history. `UiExportTest` requires a real DocumentsUI provider and compares bytes actually saved through Android's document picker. AudioTrack progress proves playback processing, not physical speaker audibility. Listen separately and test application restart plus a real generated-result export.

For UI-only testing, use the production ARM64 APK on an emulator that explicitly advertises `arm64-v8a` with ARM translation and includes DocumentsUI. The physical-device runner rejects emulator serials. Install both APKs with `adb -s "$FERROMC_EMULATOR" install -r -t <apk-path>`, then run each UI class directly, for example:

```sh
adb -s "$FERROMC_EMULATOR" shell am instrument -w -r -e class dev.ferro.seed.UiFlowTest dev.ferro.seed.test/androidx.test.runner.AndroidJUnitRunner
```

Repeat for `UiStateTest`, `UiExportTest` and `AudioPlaybackTest`. An emulator without Qualcomm HTP must refuse generation; it is not a substitute for `verify_device.py` or `NpuUiSmokeTest`.

## Actual Ferro compatibility and DSP comparison

The harness compiles unmodified Ferro project/DSP sources at commit `ea8a1451ebca207e15cb25361a6a053f9712ac48`. It requires Python 3, Git, JDK 17, a C++17 compiler, `pkg-config`, Qt 6 Core development files and libsndfile development files.

```sh
python3 tools/verify_ferro_export.py --render-dir artifacts/ferro-reference-audio > artifacts/ferro-reproduction.log 2>&1
```

Production Java exports at 40/120/300 BPM are checked for exact pitches, velocities and per-note controls, including 0/100 boundaries. The actual Ferro renderer must produce finite nonzero bounded audio. The harness edits pitch, velocity, tone, decay and drive and verifies save/reload. Optional float WAV files are test artifacts, not an additional app export feature. The reference checkout remains under `.work/ferro-reference`; modified upstream sources are rejected.

Compare the production Java float preview with freshly compiled Ferro C++ DSP:

```sh
python3 tools/verify_ferro_preview.py
```

The realistic and extreme fixtures compare 144,000 frames at 48 kHz and master .7 against both reference channels. A host sample match is not a claim about phone PCM conversion or speaker output.

To verify an actual phone export, copy the original individual `NoteRecord` JSON and the `.ferro` document into ignored `artifacts/`, then run:

```sh
python3 tools/verify_ferro_export.py --expected artifacts/device-record.json --project artifacts/generated.ferro --report artifacts/ferro-device-export-validation.json
```

The record must contain `pitches`, `velocities`, `tones`, `decays` and `drives`; it is an individual object, not the history wrapper. The harness compares the independent stored record against the loaded document and records their hashes. NPU provenance requires the separate device evidence.

## Storage and export contract

History version 2 persists exact note, velocity and per-note tone/decay/drive arrays. It accepts version 1 records lacking all three sound arrays with legacy values 50/80/20. Partial or invalid arrays fail validation. Loading preserves histories already within the limit. If an existing or recovered history exceeds 100 unliked records, loading atomically saves the retained records before exposing the store; the previous complete version remains as a backup. Successful mutations also write version 2 atomically. Likes and deletion are durable, and errors do not publish unsaved state.

Starting with v0.1.1, history retains the newest **100 unliked records**. Oldest unliked records are automatically pruned when the limit is exceeded. Liked records are excluded from this count and are preserved by pruning. Unliking a record returns it to the ordinary history limit. Validation must cover overflow, favorite preservation and reopening after an in-place APK update with existing records.

Ferro v4 exports contain six tracks, one bar with 16 steps, and a Saw chord at the first step of track 3. Every note stores `velocity / 127` and `overrides: {tone: T/100, decay: D/100, drive: R/100}`. Track defaults remain 50/80/20. Export uses 120 BPM, no samples, no delay/duck and one second of tail. Ferro has no note duration/gate field: Saw decay controls its envelope. The Java preview ports the same Saw source; app output volume, PCM conversion and device speakers remain distinct from project data.
