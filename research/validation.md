# Validation status

This summary excludes device serials, local paths, user records and screenshots. Raw reproducibility reports are generated locally under ignored `artifacts/`.

| Scope | Current result |
|---|---|
| Scratch pitch model, 82,432 parameters | Trained and independently checked; asset hash is in `model_info.json`. |
| Scratch sound model, 10,432 parameters | Trained and independently checked; combined count 92,864, asset hash in `sound_model_info.json`. |
| Domain/export tests | 14 isolated JVM tests passed, including version-1 migration failure/recovery and per-note sound validation. |
| Full current JVM suite | 41 tests passed; Android lint reported 0 errors and 11 warnings. |
| Actual Ferro loader/DSP | Three production-export tempo fixtures passed exact pitch/velocity/sound, rendering and edit/save/reload checks. |
| Incorrect timbre inputs | Tone/decay/drive mismatches, partial control arrays and missing explicit overrides were rejected. |
| Java preview versus actual Ferro DSP | Two realistic/extreme fixtures, 144,000 frames each at 48 kHz/master .7: both C++ channels match Java float samples exactly, max/RMS error 0. Host comparison only. |
| Final two-model APK on physical S24 Ultra | 32 generated results, 223 model calls, both HTP profiles verified, zero CPU graph nodes/fallback/offload. |
| Physical UI/storage/audio | Six cases passed: real two-model generation, history flow/state, actual SAF export and two AudioTrack cases. Stored per-note values survive replay/like/reopen. |
| Actual generated v4 file | A real NPU-generated result was liked and saved through physical Android SAF; the stored NoteRecord matched unchanged Ferro loading, sound-value comparison, rendering and edit/save/reload. |

The pitch model's earlier 13.28% roughness reduction refers only to its historical three-partial proxy at the documented default sampler settings. It is not a measurement of the current Saw preview. The sound model uses an approximate eight-harmonic/seven-time-point proxy; quieter or shorter notes can improve that score. Neither evaluation establishes pleasantness, creativity, power savings or an NPU speed advantage.

The final APK SHA-256 is `2186b89d6880bf9140d346e58466833026a4100a486fed9cec213711881509ee`. The strict backend run and repeated six-case physical UI suite used that exact APK. The installed APK hash was checked before and after the UI run. Raw QNN execution counts include one warm-up per model: 129 pitch and 96 sound executions; 223 excludes those two warm-ups. Machine-readable scope is in [validation.json](validation.json).

## Historical evidence

A previous single-model APK passed strict physical S24 Ultra HTP dispatch checks: 32 generated chords and 128 pitch-model calls, with CPU graph fallback disabled. Its individual generation/replay/like/reopen smoke test also passed. Five UI/storage/audio cases passed across two runs of that production ARM64 APK on an emulator with ARM translation; an actual SAF fixture export loaded and round-tripped in unchanged Ferro.

Those results predate the second model, per-note sound generation and final APK. They establish only the earlier build's behavior and are separate from the two-model checks above. Automated AudioTrack progress is not a human listening test.
