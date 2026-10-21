package dev.ferro.seed;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/** Fixed-shape HTP inference. Failure never switches the model to CPU or GPU. */
public final class NpuEngine implements AutoCloseable {
    public static final int BATCH = 16;
    public static final int WIDTH = 256;
    public static final int ELEMENTS = BATCH * WIDTH;
    public static final int SOUND_INPUT_ELEMENTS = BATCH * 32;
    public static final int SOUND_OUTPUT_ELEMENTS = BATCH * 64;
    private static final long[] SHAPE = {BATCH, WIDTH};
    private static final long[] SOUND_INPUT_SHAPE = {BATCH, 32};
    private static final long[] SOUND_OUTPUT_SHAPE = {BATCH, 64};
    private static final String INPUT = "chord_context";
    private static final String OUTPUT = "pitch_logits";
    private static final String SOUND_INPUT = "sound_context";
    private static final String SOUND_OUTPUT = "sound_logits";
    private static final String TAG = "SoriSeedNpu";
    private final Context context;
    private final JSONObject diagnostics = new JSONObject();
    private OrtEnvironment environment;
    private OrtSession session;
    private OrtSession soundSession;
    private boolean ready;
    private String state = "NPU 준비 전";
    private long inferenceCount;
    private long soundInferenceCount;

    public NpuEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Call off the UI/audio thread. Includes an actual profiled inference before ready. */
    public synchronized void initialize() throws Exception {
        if (ready) return;
        releaseSession();
        inferenceCount = 0;
        soundInferenceCount = 0;
        state = "Qualcomm NPU 확인 중";
        diagnostics.remove("error");
        final long started = System.nanoTime();
        try {
            File libraryDir = new File(context.getApplicationInfo().nativeLibraryDir);
            for (String name : new String[]{"libQnnHtp.so", "libQnnSystem.so",
                    "libQnnHtpPrepare.so", "libQnnHtpV75Stub.so", "libQnnHtpV75Skel.so"}) {
                if (!new File(libraryDir, name).isFile()) {
                    throw new IOException("NPU 라이브러리 누락: " + name
                            + ". arm64 APK와 네이티브 라이브러리 추출 설정을 확인하세요.");
                }
            }
            // FastRPC uses semicolon-separated search paths, unlike LD_LIBRARY_PATH.
            Os.setenv("ADSP_LIBRARY_PATH", libraryDir.getAbsolutePath()
                    + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp;/dsp", true);
            environment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO,
                    "sori-seed-htp");
            environment.setTelemetry(false);
            if (!OrtEnvironment.getAvailableProviders().contains(OrtProvider.QNN)) {
                throw new IOException("이 APK에 QNN 실행 공급자가 없습니다.");
            }
            byte[] model, soundModel;
            try (InputStream input = context.getAssets().open("seed_model.onnx")) {
                model = readFully(input);
            }
            try (InputStream input = context.getAssets().open("sound_model.onnx")) {
                soundModel = readFully(input);
            }
            diagnostics.put("model_sha256", sha256(model));
            diagnostics.put("sound_model_sha256", sha256(soundModel));
            Map<String, String> qnn = qnnOptions(libraryDir, "npu-profile.csv");
            Map<String, String> soundQnn = qnnOptions(libraryDir, "sound-npu-profile.csv");
            session = openSession(model, qnn, "ort-htp-proof");
            soundSession = openSession(soundModel, soundQnn, "sound-ort-htp-proof");
            validateTensorContract(session.getInputInfo(), INPUT, SHAPE);
            validateTensorContract(session.getOutputInfo(), OUTPUT, SHAPE);
            validateTensorContract(soundSession.getInputInfo(), SOUND_INPUT, SOUND_INPUT_SHAPE);
            validateTensorContract(soundSession.getOutputInfo(), SOUND_OUTPUT, SOUND_OUTPUT_SHAPE);
            diagnostics.put("ort_version", environment.getVersion());
            diagnostics.put("qnn_runtime_version", "2.42.0");
            diagnostics.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            diagnostics.put("sdk_int", Build.VERSION.SDK_INT);
            diagnostics.put("cpu_graph_fallback_disabled", true);
            diagnostics.put("io_quantization_cpu_offload", false);
            diagnostics.put("qnn_options", new JSONObject(qnn));
            diagnostics.put("sound_qnn_options", new JSONObject(soundQnn));
            diagnostics.put("input_name", INPUT);
            diagnostics.put("output_name", OUTPUT);
            diagnostics.put("shape", new JSONArray(new int[]{BATCH, WIDTH}));
            diagnostics.put("sound_input_name", SOUND_INPUT);
            diagnostics.put("sound_output_name", SOUND_OUTPUT);
            diagnostics.put("sound_input_shape", new JSONArray(new int[]{BATCH, 32}));
            diagnostics.put("sound_output_shape", new JSONArray(new int[]{BATCH, 64}));
            diagnostics.put("inference_count", 0);
            diagnostics.put("sound_inference_count", 0);
            diagnostics.put("total_inference_count", 0);
            // A provider being present is not proof that it executed. Run, then inspect dispatch.
            runModel(session, new float[ELEMENTS], INPUT, OUTPUT, SHAPE, ELEMENTS);
            String proofPath = session.endProfiling();
            diagnostics.put("ort_profile", proofPath);
            runModel(soundSession, new float[SOUND_INPUT_ELEMENTS], SOUND_INPUT, SOUND_OUTPUT,
                    SOUND_INPUT_SHAPE, SOUND_OUTPUT_ELEMENTS);
            String soundProofPath = soundSession.endProfiling();
            int[][] proofCounts = requireBothQnnDispatch(readProfile(proofPath), readProfile(soundProofPath));
            diagnostics.put("profile_qnn_node_events", proofCounts[0][0]);
            diagnostics.put("profile_non_qnn_node_events", proofCounts[0][1]);
            diagnostics.put("sound_profile_qnn_node_events", proofCounts[1][0]);
            diagnostics.put("sound_profile_non_qnn_node_events", proofCounts[1][1]);
            diagnostics.put("sound_ort_profile", soundProofPath);
            diagnostics.put("warmup_model_calls", 2);
            diagnostics.put("initialization_ms", (System.nanoTime() - started) / 1_000_000.0);
            ready = true;
            state = "Qualcomm HTP · 2개 모델 NPU 추론 확인 · CPU 폴백 차단";
            diagnostics.put("status", "htp_verified");
            saveDiagnostics();
            Log.i(TAG, state + " model=" + diagnostics.optString("model_sha256"));
        } catch (Exception | LinkageError error) {
            fail(error);
            throw new IllegalStateException(state, error);
        }
    }

    public synchronized float[] infer(float[] input) throws Exception {
        return inferModel(input, false);
    }

    public synchronized float[] inferSound(float[] input) throws Exception {
        return inferModel(input, true);
    }

    private float[] inferModel(float[] input, boolean sound) throws Exception {
        if (!ready || session == null || soundSession == null) throw new IllegalStateException(state);
        int expected = sound ? SOUND_INPUT_ELEMENTS : ELEMENTS;
        if (input == null || input.length != expected)
            throw new IllegalArgumentException(sound ? "입력은 float32 [16,32]이어야 합니다."
                    : "입력은 float32 [16,256]이어야 합니다.");
        for (float value : input) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("입력에 유한하지 않은 값이 있습니다.");
        }
        try {
            long started = System.nanoTime();
            float[] output = sound
                    ? runModel(soundSession, input, SOUND_INPUT, SOUND_OUTPUT, SOUND_INPUT_SHAPE, SOUND_OUTPUT_ELEMENTS)
                    : runModel(session, input, INPUT, OUTPUT, SHAPE, ELEMENTS);
            diagnostics.put(sound ? "sound_last_inference_ms" : "last_inference_ms",
                    (System.nanoTime() - started) / 1_000_000.0);
            if (sound) diagnostics.put("sound_inference_count", ++soundInferenceCount);
            else diagnostics.put("inference_count", ++inferenceCount);
            diagnostics.put("total_inference_count", inferenceCount + soundInferenceCount);
            saveDiagnostics();
            return output;
        } catch (Exception | LinkageError error) {
            fail(error);
            throw new IllegalStateException(state, error);
        }
    }

    public synchronized boolean initialized() { return ready; }

    public synchronized String status() { return state; }

    private Map<String, String> qnnOptions(File libraryDir, String csvName) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("backend_path", new File(libraryDir, "libQnnHtp.so").getAbsolutePath());
        options.put("soc_model", "57");
        options.put("htp_arch", "75");
        options.put("device_id", "0");
        options.put("offload_graph_io_quantization", "0");
        options.put("htp_performance_mode", "balanced");
        options.put("profiling_level", "detailed");
        options.put("profiling_file_path", new File(context.getFilesDir(), csvName).getAbsolutePath());
        return options;
    }

    private OrtSession openSession(byte[] model, Map<String, String> qnn, String profilePrefix) throws Exception {
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO);
            options.setLoggerId("ferromc-htp-only-" + profilePrefix);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.enableProfiling(new File(context.getFilesDir(), profilePrefix).getAbsolutePath());
            options.addQnn(qnn);
            return environment.createSession(model, options);
        }
    }

    private float[] runModel(OrtSession active, float[] input, String inputName, String outputName,
                            long[] inputShape, int outputElements) throws Exception {
        FloatBuffer buffer = ByteBuffer.allocateDirect(input.length * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(input).rewind();
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, buffer, inputShape);
             OrtSession.Result result = active.run(Collections.singletonMap(inputName, tensor))) {
            OnnxValue value = result.get(outputName).orElseThrow(
                    () -> new IOException("모델 출력 " + outputName + "가 없습니다."));
            if (!(value instanceof OnnxTensor)) throw new IOException("잘못된 출력 텐서 형식");
            FloatBuffer outputBuffer = ((OnnxTensor) value).getFloatBuffer();
            if (outputBuffer == null || outputBuffer.remaining() != outputElements) {
                throw new IOException("잘못된 출력 텐서 크기");
            }
            float[] output = new float[outputElements];
            outputBuffer.get(output);
            for (float item : output) {
                if (!Float.isFinite(item)) throw new IOException("NPU 출력에 유한하지 않은 값이 있습니다.");
            }
            return output;
        }
    }

    private static void validateTensorContract(Map<String, NodeInfo> tensors, String name, long[] shape)
            throws IOException {
        NodeInfo node = tensors.get(name);
        if (tensors.size() != 1 || node == null || !(node.getInfo() instanceof TensorInfo)) {
            throw new IOException("모델 텐서 계약 불일치: " + name);
        }
        TensorInfo info = (TensorInfo) node.getInfo();
        if (info.type != OnnxJavaType.FLOAT || !Arrays.equals(info.getShape(), shape)) {
            throw new IOException("모델 텐서는 float32 " + Arrays.toString(shape) + "이어야 합니다: " + name);
        }
    }

    private static JSONArray readProfile(String path) throws Exception {
        if (path == null || path.isEmpty()) throw new IOException("NPU 실행 프로파일을 만들지 못했습니다.");
        JSONArray events;
        try (InputStream input = new FileInputStream(path)) {
            events = new JSONArray(new String(readFully(input), StandardCharsets.UTF_8));
        }
        return events;
    }

    static int[][] requireBothQnnDispatch(JSONArray pitchEvents, JSONArray soundEvents) throws IOException {
        // Neither a valid pitch graph nor provider registration can stand in for sound execution.
        return new int[][]{requireQnnDispatch(pitchEvents), requireQnnDispatch(soundEvents)};
    }

    static int[] requireQnnDispatch(JSONArray events) throws IOException {
        int qnnNodes = 0;
        int cpuNodes = 0;
        for (int i = 0; i < events.length(); ++i) {
            JSONObject event = events.optJSONObject(i);
            if (event == null || !"Node".equals(event.optString("cat"))) continue;
            String name = event.optString("name");
            if (name.endsWith("_fence_before") || name.endsWith("_fence_after")) continue;
            JSONObject args = event.optJSONObject("args");
            String provider = args == null ? "" : args.optString("provider");
            if (provider.isEmpty()) throw new IOException("실행 공급자가 없는 노드: " + name);
            if ("QNNExecutionProvider".equals(provider)) ++qnnNodes;
            else ++cpuNodes;
        }
        if (qnnNodes == 0 || cpuNodes != 0) {
            throw new IOException("NPU 전용 실행을 확인하지 못했습니다: QNN=" + qnnNodes
                    + ", 다른 공급자=" + cpuNodes);
        }
        return new int[]{qnnNodes, cpuNodes};
    }

    private void fail(Throwable error) {
        ready = false;
        state = "NPU 실행 불가: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        Log.e(TAG, state, error);
        try {
            diagnostics.put("status", "failed");
            diagnostics.put("error", state);
            saveDiagnostics();
        } catch (Exception loggingFailure) {
            Log.e(TAG, "진단 파일 저장 실패", loggingFailure);
        }
        releaseSession();
    }

    private void saveDiagnostics() throws Exception {
        diagnostics.put("updated_at_epoch_ms", System.currentTimeMillis());
        File temp = new File(context.getFilesDir(), "npu-diagnostics.json.tmp");
        File target = new File(context.getFilesDir(), "npu-diagnostics.json");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(diagnostics.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
    }

    private void releaseSession() {
        if (soundSession != null) {
            try { soundSession.close(); }
            catch (Exception error) { Log.w(TAG, "음색 HTP 세션 종료 오류", error); }
            soundSession = null;
        }
        if (session != null) {
            try { session.close(); }
            catch (Exception error) { Log.w(TAG, "HTP 세션 종료 오류", error); }
            session = null;
        }
    }

    @Override public synchronized void close() {
        ready = false;
        releaseSession();
        state = "NPU 세션 종료";
        // OrtEnvironment is process-wide; closing it could affect another owner.
    }

    private static byte[] readFully(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }
}
