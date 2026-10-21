package dev.ferro.seed;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Run explicitly on the connected S24 Ultra. Missing HTP is a failure, never a skip. */
@RunWith(AndroidJUnit4.class)
public final class HtpDeviceTest {
    @Test public void actualHtpGeneratesThirtyTwoChordsWithoutCpuFallback() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File reportFile = new File(context.getFilesDir(), "htp-device-validation.json");
        JSONObject report = new JSONObject()
                .put("schema_version", 2).put("status", "running")
                .put("run_id", InstrumentationRegistry.getArguments().getString("sori_validation_run_id", "manual"))
                .put("device_execution_verified", false)
                .put("started_at_epoch_ms", System.currentTimeMillis())
                .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("soc_model", Build.SOC_MODEL).put("sdk_int", Build.VERSION.SDK_INT);
        JSONArray generations = new JSONArray();
        report.put("generations", generations);
        writeReport(reportFile, report);
        long started = SystemClock.elapsedRealtimeNanos();
        NpuEngine engine = new NpuEngine(context);
        final int[] calls = {0}, soundCalls = {0};
        try {
            // initialize runs inference and checks actual ORT node dispatch.
            engine.initialize();
            assertTrue("HTP initialization did not complete", engine.initialized());
            report.put("htp_initialized", true);
            Set<String> differentChords = new HashSet<>();
            int[][] ranges = {{36, 71}, {48, 83}, {36, 99}};
            for (int i = 0; i < 32; i++) {
                int count = 2 + i % 3;
                int[] range = ranges[(i / 3) % ranges.length];
                double exploration = (i % 5) / 4.0;
                long seed = 0x534F524953454544L + i;
                int previousCalls = calls[0];
                int previousSoundCalls = soundCalls[0];
                long generationStart = SystemClock.elapsedRealtimeNanos();
                int[] notes = ChordSampler.generate(input -> {
                    float[] output = engine.infer(input);
                    calls[0]++;
                    return output;
                }, seed, count, exploration, range[0], range[1]);
                assertEquals("Every generation must execute all four model passes", 4,
                        calls[0] - previousCalls);
                assertEquals("Wrong number of notes", count, notes.length);
                SoundSampler.Result sound = SoundSampler.generate(input -> {
                    float[] output = engine.inferSound(input);
                    soundCalls[0]++;
                    return output;
                }, seed ^ 0x464552524F4D4353L, notes, exploration);
                assertEquals("Every note must execute the sound model", count,
                        soundCalls[0] - previousSoundCalls);
                assertEquals(count, sound.velocities.length);
                assertEquals(count, sound.tones.length);
                assertEquals(count, sound.decays.length);
                assertEquals(count, sound.drives.length);
                Set<Integer> unique = new HashSet<>();
                for (int n = 0; n < notes.length; ++n) {
                    assertTrue("Pitch outside requested range", notes[n] >= range[0] && notes[n] <= range[1]);
                    assertTrue("Duplicate pitch", unique.add(notes[n]));
                    if (n != 0) assertTrue("Notes must be ordered", notes[n - 1] < notes[n]);
                    assertTrue("Generated velocity outside range", sound.velocities[n] >= 16 && sound.velocities[n] <= 127);
                    assertTrue("Generated tone outside range", sound.tones[n] >= 0 && sound.tones[n] <= 100);
                    assertTrue("Generated decay outside range", sound.decays[n] >= 0 && sound.decays[n] <= 100);
                    assertTrue("Generated drive outside range", sound.drives[n] >= 0 && sound.drives[n] <= 100);
                }
                JSONArray pitches = new JSONArray(notes);
                differentChords.add(pitches.toString());
                generations.put(new JSONObject().put("seed", seed).put("count", count)
                        .put("low_pitch", range[0]).put("high_pitch", range[1])
                        .put("exploration", exploration).put("pitches", pitches)
                        .put("velocities", new JSONArray(sound.velocities))
                        .put("tones", new JSONArray(sound.tones))
                        .put("decays", new JSONArray(sound.decays))
                        .put("drives", new JSONArray(sound.drives))
                        .put("model_calls", calls[0] - previousCalls)
                        .put("sound_model_calls", soundCalls[0] - previousSoundCalls)
                        .put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - generationStart) / 1_000_000.0));
            }
            assertEquals(128, calls[0]);
            assertEquals(95, soundCalls[0]);
            assertTrue("All samples unexpectedly collapsed to one output", differentChords.size() > 1);
            JSONObject diagnostic = new JSONObject(new String(Files.readAllBytes(
                    new File(context.getFilesDir(), "npu-diagnostics.json").toPath()), StandardCharsets.UTF_8));
            assertEquals("htp_verified", diagnostic.getString("status"));
            assertTrue(diagnostic.getBoolean("cpu_graph_fallback_disabled"));
            assertFalse(diagnostic.getBoolean("io_quantization_cpu_offload"));
            assertTrue(diagnostic.getInt("profile_qnn_node_events") > 0);
            assertEquals(0, diagnostic.getInt("profile_non_qnn_node_events"));
            assertTrue(diagnostic.getInt("sound_profile_qnn_node_events") > 0);
            assertEquals(0, diagnostic.getInt("sound_profile_non_qnn_node_events"));
            assertEquals(128, diagnostic.getLong("inference_count"));
            assertEquals(95, diagnostic.getLong("sound_inference_count"));
            assertEquals(223, diagnostic.getLong("total_inference_count"));
            assertEquals(2, diagnostic.getInt("warmup_model_calls"));
            report.put("npu_diagnostics", diagnostic).put("distinct_chords", differentChords.size());
            report.put("status", "passed").put("device_execution_verified", true);
        } catch (Exception | AssertionError failure) {
            report.put("status", "failed");
            report.put("error", failure.getClass().getName() + ": " + failure.getMessage());
            throw failure;
        } finally {
            engine.close();
            report.put("model_inference_calls", calls[0]);
            report.put("sound_model_inference_calls", soundCalls[0]);
            report.put("total_model_inference_calls", calls[0] + soundCalls[0]);
            report.put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0);
            report.put("finished_at_epoch_ms", System.currentTimeMillis());
            writeReport(reportFile, report);
        }
    }

    private static void writeReport(File file, JSONObject report) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }
}
