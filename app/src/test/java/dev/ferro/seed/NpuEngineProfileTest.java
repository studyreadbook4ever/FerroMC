package dev.ferro.seed;

import org.json.JSONArray;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

public final class NpuEngineProfileTest {
    @Test public void translatedArmAbiCannotLoadTheNativeRuntime() throws Exception {
        NpuEngine.requireNativeArm64(new String[]{"arm64-v8a", "armeabi-v7a"});
        assertThrows(IOException.class, () -> NpuEngine.requireNativeArm64(new String[]{"x86_64", "arm64-v8a"}));
        assertThrows(IOException.class, () -> NpuEngine.requireNativeArm64(new String[]{"x86"}));
        assertThrows(IOException.class, () -> NpuEngine.requireNativeArm64(new String[0]));
        assertThrows(IOException.class, () -> NpuEngine.requireNativeArm64(null));
    }

    private static final String QNN = "{\"cat\":\"Node\",\"name\":\"QNN_0_kernel_time\","
            + "\"args\":{\"provider\":\"QNNExecutionProvider\"}}";

    @Test public void acceptsExecutedQnnGraphAndIgnoresSessionAndFences() throws Exception {
        JSONArray events = new JSONArray("[{\"cat\":\"Session\",\"name\":\"model_loading\"},"
                + "{\"cat\":\"Node\",\"name\":\"QNN_0_fence_before\"}," + QNN + ","
                + "{\"cat\":\"Node\",\"name\":\"QNN_0_fence_after\"}]");
        assertArrayEquals(new int[]{1, 0}, NpuEngine.requireQnnDispatch(events));
    }

    @Test public void rejectsCpuGraphEvenWhenQnnAlsoRan() throws Exception {
        JSONArray events = new JSONArray("[" + QNN + ","
                + "{\"cat\":\"Node\",\"name\":\"QDQ_kernel_time\","
                + "\"args\":{\"provider\":\"CPUExecutionProvider\"}}]");
        assertThrows(IOException.class, () -> NpuEngine.requireQnnDispatch(events));
    }

    @Test public void rejectsOtherAccelerator() throws Exception {
        JSONArray events = new JSONArray("[{\"cat\":\"Node\",\"name\":\"kernel_time\","
                + "\"args\":{\"provider\":\"NNAPIExecutionProvider\"}}]");
        assertThrows(IOException.class, () -> NpuEngine.requireQnnDispatch(events));
    }

    @Test public void rejectsProviderRegistrationWithoutInference() throws Exception {
        JSONArray events = new JSONArray("[{\"cat\":\"Session\",\"name\":\"session_initialization\"}]");
        assertThrows(IOException.class, () -> NpuEngine.requireQnnDispatch(events));
    }

    @Test public void rejectsUnknownNodeWithoutProviderEvenAfterQnn() throws Exception {
        JSONArray events = new JSONArray("[" + QNN + ","
                + "{\"cat\":\"Node\",\"name\":\"unknown_kernel_time\",\"args\":{}}]");
        assertThrows(IOException.class, () -> NpuEngine.requireQnnDispatch(events));
    }

    @Test public void acceptsOnlyWhenBothIndependentGraphsExecutedOnQnn() throws Exception {
        int[][] counts = NpuEngine.requireBothQnnDispatch(new JSONArray("[" + QNN + "]"),
                new JSONArray("[" + QNN + "," + QNN + "]"));
        assertArrayEquals(new int[]{1, 0}, counts[0]);
        assertArrayEquals(new int[]{2, 0}, counts[1]);
    }

    @Test public void validPitchCannotHideMissingSoundExecution() throws Exception {
        assertThrows(IOException.class, () -> NpuEngine.requireBothQnnDispatch(
                new JSONArray("[" + QNN + "]"), new JSONArray()));
    }

    @Test public void validSoundCannotHidePitchCpuFallbackAndViceVersa() throws Exception {
        JSONArray cpu = new JSONArray("[{\"cat\":\"Node\",\"name\":\"kernel_time\","
                + "\"args\":{\"provider\":\"CPUExecutionProvider\"}}]");
        JSONArray qnn = new JSONArray("[" + QNN + "]");
        assertThrows(IOException.class, () -> NpuEngine.requireBothQnnDispatch(cpu, qnn));
        assertThrows(IOException.class, () -> NpuEngine.requireBothQnnDispatch(qnn, cpu));
    }
}
