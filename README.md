# FerroMC

Galaxy S24 Ultra에서 NPU로 짧은 음 조합을 탐색하는 Android 앱입니다. 음정과 함께 각 음의 **TONE·DECAY·DRIVE·VELOCITY**를 생성하고, 마음에 드는 결과를 [Ferro](https://github.com/studyreadbook4ever/Ferro)에서 이어 편집할 수 있습니다.

**현재 검증:** 두 모델의 학습·수치 검증, JVM 테스트 41개, 실제 Ferro의 음별 속성 로드·렌더·편집·재저장 검사를 통과했습니다. 실물 S24 Ultra에서 두 모델의 엄격한 HTP 실행과 UI·저장·오디오 6개 검사를 통과했고, Java 미리듣기는 두 고정 예제에서 실제 Ferro DSP와 샘플 단위로 일치했습니다. APK별 검증 범위와 한계는 [검증 상태](research/validation.md)를 참고하세요.

## 사용

1. **음 생성**을 눌러 2~4개 음을 만들고 **재생**으로 다시 듣습니다.
2. **과거 생성기록 보기**에서 듣기·좋아요·삭제를 할 수 있습니다. 좋아요는 위에 모이고 처음에는 최근 일반 기록이 보입니다.
3. 좋아요를 누른 결과의 **.ferro 내보내기**를 선택합니다. 저장한 파일은 사용자가 직접 PC로 옮깁니다.
4. Ferro에서 음정·velocity와 음별 tone·decay·drive를 편집합니다.

앱은 정확한 생성값을 기기에 저장합니다. 재생할 때 모델을 다시 실행하지 않으며 좋아요로 재학습하지 않습니다. 미리듣기는 Ferro의 Saw DSP를 Java로 이식해 사용하고, 앱 음량과 출력 경로는 별도입니다. 파일은 편집 가능한 프로젝트이며 별도 WAV나 메타데이터를 내보내지 않습니다.

## 모델과 실행

| 모델 | 파라미터 | 입출력 |
|---|---:|---|
| 음정 MLP `256 → 128 → 128 → 256` | 82,432 | `float32[16,256] → [16,256]` |
| 음색 MLP `32 → 64 → 64 → 64` | 10,432 | `float32[16,32] → [16,64]` |
| 합계 | **92,864** | 정적 U8/U8 QDQ ONNX |

두 모델 모두 합성 데이터와 무작위 초기 가중치로 학습했습니다. 사전학습 모델, 음악 데이터셋, 조성·코드 이름·화음 진행 규칙을 사용하지 않습니다. 약한 음향 거칠기 지표로 확률을 조정하며, 이 지표는 청감이나 창의성을 입증하지 않습니다. 음정 모델은 과거의 세 배음 기준음을, 음색 모델은 Ferro Saw의 근사 스펙트럼을 학습에 사용합니다.

신경망은 Qualcomm HTP V75를 지정하고 CPU 그래프 폴백을 차단합니다. 두 모델의 실제 초기 실행 프로파일을 확인해야 생성이 활성화됩니다. 입력 준비·확률 샘플링·오디오 합성은 CPU에서 실행합니다. HTP를 준비하지 못하면 생성을 막고, 저장된 기록의 재생과 내보내기는 제공합니다. 기기에서 학습하지 않습니다.

## 빌드와 재현

JDK 17, Android SDK 35가 필요합니다. 패키지는 `dev.ferro.seed`, 대상은 ARM64 Snapdragon 8 Gen 3 / HTP V75입니다.

```sh
python3 tools/setup_qnn_runtime.py
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. [재현 절차](research/reproduction.md)에 학습·실물 기기·Ferro 검증 명령이 있고, [NPU 통합 설명](research/npu-integration.md)에 런타임 조건을 기록했습니다.

프로젝트의 원본 코드와 직접 학습한 모델 가중치는 [Unlicense](LICENSE)로 제공합니다. ONNX Runtime, Qualcomm QNN 및 빌드·테스트 의존성은 각각의 라이선스가 적용됩니다. [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)를 확인하세요. 이 저장소에는 Qualcomm SDK 바이너리나 빌드된 APK를 포함하지 않습니다.
