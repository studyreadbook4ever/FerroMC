package dev.ferro.seed;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Run explicitly on a physical Samsung S24 Ultra. Unsupported devices FAIL, never skip. */
@RunWith(AndroidJUnit4.class)
public final class NpuUiSmokeTest {
    private static final long STARTUP_TIMEOUT = 60_000;
    private static final long UI_TIMEOUT = 15_000;
    private static final long GENERATION_TIMEOUT = 30_000;

    @Test public void actualUiGenerationPersistsReplaysAndRemembersLike() throws Throwable {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        UiDevice device = UiDevice.getInstance(instrumentation);
        File reportFile = new File(context.getFilesDir(), "npu-ui-smoke.json");
        long started = SystemClock.elapsedRealtime();
        JSONObject report = new JSONObject()
                .put("schema_version", 1).put("status", "running")
                .put("run_id", InstrumentationRegistry.getArguments().getString("sori_validation_run_id", "manual"))
                .put("device_execution_verified", false).put("ui_smoke_verified", false)
                .put("started_at_epoch_ms", System.currentTimeMillis())
                .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("hardware", Build.HARDWARE).put("soc_model", Build.SOC_MODEL)
                .put("sdk_int", Build.VERSION.SDK_INT)
                .put("successful_npu_mock_used", false);
        writeReport(reportFile, report);

        HistoryStore store = null;
        Map<String, String> originalRecords = new LinkedHashMap<>();
        Set<String> protectedIds = new HashSet<>();
        AtomicReference<NoteRecord> generated = new AtomicReference<>();
        List<Launch> launches = new ArrayList<>();
        boolean generationClicked = false;
        long clickedAtEpoch = 0;
        Throwable primaryFailure = null;
        try {
            requirePhysicalS24Ultra();
            report.put("physical_s24_ultra_guard_passed", true);
            store = HistoryStore.shared(context);
            for (NoteRecord record : store.list()) {
                originalRecords.put(record.id, record.toJson().toString());
                protectedIds.add(record.id);
            }
            report.put("existing_record_count", originalRecords.size());

            Launch first = launch(instrumentation, context);
            launches.add(first);
            awaitEnabled(device, By.desc("음 생성"), STARTUP_TIMEOUT,
                    "The real MainActivity must enable generation after HTP initialization within 60 seconds.");
            JSONObject before = diagnostics(context);
            assertHtpProof(before);
            long beforeCalls = before.getLong("inference_count");
            long beforeSoundCalls = before.getLong("sound_inference_count");
            report.put("initialization_diagnostics", before);
            writeReport(reportFile, report);

            // Preserve records that appeared while startup was in progress as well.
            for (NoteRecord record : store.list()) protectedIds.add(record.id);
            assertTrue("This UI smoke needs one free recent-history slot to preserve user records.",
                    store.list().stream().filter(r -> !r.liked).count() < HistoryStore.MAX_RECENT_RECORDS);
            clickedAtEpoch = System.currentTimeMillis();
            generationClicked = true;
            long generationStarted = SystemClock.elapsedRealtime();
            await(device, By.desc("음 생성")).click();
            HistoryStore activeStore = store;
            eventually("One actual UI generation must save and select a new UUID record.", GENERATION_TIMEOUT, () -> {
                NoteRecord selected = field(instrumentation, first.activity, "current", NoteRecord.class);
                if (selected == null || protectedIds.contains(selected.id)) return false;
                assertCanonicalUuid(selected.id);
                generated.set(selected.copy());
                NoteRecord persisted = activeStore.get(selected.id);
                return persisted != null && !field(instrumentation, first.activity, "generating", Boolean.class);
            });
            NoteRecord result = generated.get();
            assertNotNull(result);
            assertFalse("A new generated record starts unliked.", result.liked);
            NoteRecord persisted = store.get(result.id);
            assertRecordEquals(result, persisted);
            await(device, By.text(ChordSampler.describe(persisted.pitches)));
            assertEquals(result.id, field(instrumentation, first.activity, "current", NoteRecord.class).id);

            JSONObject after = diagnostics(context);
            assertHtpProof(after);
            assertEquals("The real generation button must execute four model passes.",
                    4L, after.getLong("inference_count") - beforeCalls);
            assertEquals("Sound generation must run once per note.", result.pitches.length,
                    after.getLong("sound_inference_count") - beforeSoundCalls);
            for (int i = 0; i < result.pitches.length; i++) {
                assertTrue(result.tones[i] >= 0 && result.tones[i] <= 100);
                assertTrue(result.decays[i] >= 0 && result.decays[i] <= 100);
                assertTrue(result.drives[i] >= 0 && result.drives[i] <= 100);
                assertTrue(result.velocities[i] >= 16 && result.velocities[i] <= 127);
            }
            report.put("generation_sound_model_calls", after.getLong("sound_inference_count") - beforeSoundCalls);
            report.put("generation_elapsed_ms", SystemClock.elapsedRealtime() - generationStarted)
                    .put("generated_record", persisted.toJson())
                    .put("generation_diagnostics", after)
                    .put("generation_model_calls", after.getLong("inference_count") - beforeCalls)
                    .put("stored_notes_match_home_title", true)
                    .put("device_execution_verified", true);
            writeReport(reportFile, report);

            long playbackBefore = field(instrumentation, first.activity, "playbackToken", Long.class);
            await(device, By.desc("재생")).click();
            eventually("The actual playback control must return after playing stored notes.", UI_TIMEOUT, () -> {
                long token = field(instrumentation, first.activity, "playbackToken", Long.class);
                boolean playing = field(instrumentation, first.activity, "playing", Boolean.class);
                UiObject2 replay = device.findObject(By.desc("재생"));
                return token > playbackBefore && !playing && replay != null && replay.isEnabled();
            });
            assertEquals("Replay must not trigger another neural generation.",
                    after.getLong("inference_count"), diagnostics(context).getLong("inference_count"));
            assertEquals("Replay must not run the sound model.", after.getLong("sound_inference_count"),
                    diagnostics(context).getLong("sound_inference_count"));
            report.put("playback_control_recovered", true)
                    .put("physical_audio_audibility_asserted", false);

            await(device, By.desc("현재 음 좋아요")).click();
            eventually("The actual like action must be durably saved.", UI_TIMEOUT, () -> {
                NoteRecord saved = activeStore.get(result.id);
                return saved != null && saved.liked;
            });
            drainQueuedWork(first.worker);
            drainQueuedWork(first.historyWorker);
            instrumentation.waitForIdleSync();
            assertTrue(await(device, By.desc("현재 음 좋아요")).getText().contains("♥"));
            NoteRecord liked = store.get(result.id);
            assertArrayEquals(result.pitches, liked.pitches);
            assertArrayEquals(result.velocities, liked.velocities);
            assertArrayEquals(result.tones, liked.tones);
            assertArrayEquals(result.decays, liked.decays);
            assertArrayEquals(result.drives, liked.drives);
            report.put("like_saved", true);

            // Finish the real Activity and its engine before starting a fresh Activity.
            finishAndDrain(instrumentation, first);
            NoteRecord diskRecord = new HistoryStore(new File(context.getFilesDir(), "generated-history.json"))
                    .get(result.id);
            assertRecordEquals(liked, diskRecord);
            Launch reopened = launch(instrumentation, context);
            launches.add(reopened);
            await(device, By.text(ChordSampler.describe(liked.pitches)));
            NoteRecord restored = field(instrumentation, reopened.activity, "current", NoteRecord.class);
            assertRecordEquals(liked, restored);
            assertTrue(await(device, By.desc("현재 음 좋아요")).getText().contains("♥"));
            assertTrue("Stored results must remain playable after reopening.",
                    await(device, By.desc("재생")).isEnabled());
            report.put("activity_reopened", true).put("same_notes_and_like_restored", true)
                    .put("status", "passed").put("ui_smoke_verified", true);
        } catch (Throwable failure) {
            primaryFailure = failure;
            report.put("status", "failed").put("error", failure.getClass().getName() + ": " + failure.getMessage());
            try {
                report.put("last_npu_diagnostics", diagnostics(context));
            } catch (Exception unavailable) {
                report.put("last_npu_diagnostics_unavailable", unavailable.getMessage());
            }
            throw failure;
        } finally {
            List<Throwable> cleanupFailures = new ArrayList<>();
            // Let an already-issued generation publish its UUID before finishing its Activity.
            if (generationClicked && generated.get() == null && !launches.isEmpty()) {
                Launch first = launches.get(0);
                try {
                    if (!first.closed) {
                        drainQueuedWork(first.worker);
                        drainQueuedWork(first.historyWorker);
                        instrumentation.waitForIdleSync();
                    }
                    NoteRecord selected = field(instrumentation, first.activity, "current", NoteRecord.class);
                    if (selected != null && !protectedIds.contains(selected.id)) {
                        assertCanonicalUuid(selected.id);
                        generated.set(selected.copy());
                    }
                } catch (Throwable failure) {
                    cleanupFailures.add(failure);
                }
            }
            for (int index = launches.size() - 1; index >= 0; index--) {
                try {
                    finishAndDrain(instrumentation, launches.get(index));
                } catch (Throwable failure) {
                    cleanupFailures.add(failure);
                }
            }
            if (store != null) {
                try {
                    // A failed UI observation can still leave the single real generation on disk.
                    // Only claim that UUID if it is the sole new record created after our click.
                    if (generationClicked && generated.get() == null) {
                        List<NoteRecord> candidates = new ArrayList<>();
                        for (NoteRecord record : store.list()) {
                            if (!protectedIds.contains(record.id) && record.createdAt >= clickedAtEpoch)
                                candidates.add(record);
                        }
                        if (candidates.size() == 1) {
                            assertCanonicalUuid(candidates.get(0).id);
                            generated.set(candidates.get(0));
                        } else if (candidates.size() > 1) {
                            throw new AssertionError("Multiple unowned new records appeared; none were deleted to protect user data.");
                        }
                    }
                    NoteRecord owned = generated.get();
                    if (owned != null) {
                        assertFalse("Never delete a pre-existing user ID.", protectedIds.contains(owned.id));
                        store.delete(owned.id);
                        assertNull(store.get(owned.id));
                        report.put("test_generated_uuid", owned.id).put("test_generated_record_cleaned_up", true);
                    }
                    Map<String, String> remaining = new LinkedHashMap<>();
                    for (NoteRecord record : store.list()) remaining.put(record.id, record.toJson().toString());
                    for (Map.Entry<String, String> entry : originalRecords.entrySet())
                        assertEquals("Existing user record must remain unchanged: " + entry.getKey(),
                                entry.getValue(), remaining.get(entry.getKey()));
                    report.put("existing_records_preserved", true);
                } catch (Throwable failure) {
                    cleanupFailures.add(failure);
                }
            }
            if (!cleanupFailures.isEmpty()) {
                JSONArray errors = new JSONArray();
                for (Throwable failure : cleanupFailures) errors.put(failure.getClass().getName() + ": " + failure.getMessage());
                report.put("status", "failed").put("cleanup_errors", errors);
            }
            report.put("finished_at_epoch_ms", System.currentTimeMillis())
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            try {
                writeReport(reportFile, report);
            } catch (Throwable failure) {
                cleanupFailures.add(failure);
            }
            if (!cleanupFailures.isEmpty()) {
                if (primaryFailure != null) {
                    for (Throwable failure : cleanupFailures) primaryFailure.addSuppressed(failure);
                } else {
                    Throwable first = cleanupFailures.get(0);
                    for (int i = 1; i < cleanupFailures.size(); i++) first.addSuppressed(cleanupFailures.get(i));
                    throw first;
                }
            }
        }
    }

    private static void requirePhysicalS24Ultra() {
        String fingerprint = Build.FINGERPRINT.toLowerCase(Locale.ROOT);
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        String product = Build.PRODUCT.toLowerCase(Locale.ROOT);
        assertFalse("This acceptance test requires a physical S24 Ultra; an emulator is an explicit failure.",
                fingerprint.startsWith("generic") || hardware.contains("goldfish") || hardware.contains("ranchu")
                        || product.contains("sdk") || Build.MODEL.toLowerCase(Locale.ROOT).contains("emulator"));
        assertEquals("This acceptance test requires a Samsung device.", "samsung",
                Build.MANUFACTURER.toLowerCase(Locale.ROOT));
        assertTrue("This acceptance test is specifically for the S24 Ultra (SM-S928 family).",
                Build.MODEL.toUpperCase(Locale.ROOT).startsWith("SM-S928"));
    }

    private static void assertCanonicalUuid(String id) {
        assertEquals("Generated records must use UUID IDs.", id.toLowerCase(Locale.ROOT), UUID.fromString(id).toString());
    }

    private static void assertRecordEquals(NoteRecord expected, NoteRecord actual) {
        assertNotNull("The generated record must exist.", actual);
        assertEquals(expected.id, actual.id);
        assertEquals(expected.createdAt, actual.createdAt);
        assertEquals(expected.seed, actual.seed);
        assertEquals(expected.modelVersion, actual.modelVersion);
        assertEquals(expected.liked, actual.liked);
        assertArrayEquals(expected.pitches, actual.pitches);
        assertArrayEquals(expected.velocities, actual.velocities);
        assertArrayEquals(expected.tones, actual.tones);
        assertArrayEquals(expected.decays, actual.decays);
        assertArrayEquals(expected.drives, actual.drives);
    }

    private static JSONObject diagnostics(Context context) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(
                new File(context.getFilesDir(), "npu-diagnostics.json").toPath()), StandardCharsets.UTF_8));
    }

    private static void assertHtpProof(JSONObject diagnostics) throws Exception {
        assertEquals("htp_verified", diagnostics.getString("status"));
        assertTrue(diagnostics.getBoolean("cpu_graph_fallback_disabled"));
        assertFalse(diagnostics.getBoolean("io_quantization_cpu_offload"));
        assertTrue(diagnostics.getInt("profile_qnn_node_events") > 0);
        assertEquals(0, diagnostics.getInt("profile_non_qnn_node_events"));
        assertTrue(diagnostics.getInt("sound_profile_qnn_node_events") > 0);
        assertEquals(0, diagnostics.getInt("sound_profile_non_qnn_node_events"));
    }

    private static final class Launch {
        final Activity activity;
        final ExecutorService worker;
        final ExecutorService historyWorker;
        boolean closed;
        Launch(Activity activity, ExecutorService worker, ExecutorService historyWorker) {
            this.activity = activity;
            this.worker = worker;
            this.historyWorker = historyWorker;
        }
    }

    private static Launch launch(Instrumentation instrumentation, Context context) {
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        return new Launch(activity, field(instrumentation, activity, "worker", ExecutorService.class),
                field(instrumentation, activity, "historyWorker", ExecutorService.class));
    }

    private static void drainQueuedWork(ExecutorService worker) throws Exception {
        if (worker.isShutdown()) return;
        CountDownLatch finished = new CountDownLatch(1);
        worker.execute(finished::countDown);
        assertTrue("The Activity worker must finish its queued actions.",
                finished.await(STARTUP_TIMEOUT, TimeUnit.MILLISECONDS));
    }

    private static void finishAndDrain(Instrumentation instrumentation, Launch launch) throws Exception {
        if (launch.closed) return;
        instrumentation.runOnMainSync(launch.activity::finish);
        instrumentation.waitForIdleSync();
        assertTrue("The finished Activity must close its worker and NPU session.",
                launch.worker.awaitTermination(STARTUP_TIMEOUT, TimeUnit.MILLISECONDS));
        assertTrue("The finished Activity must finish its storage actions.",
                launch.historyWorker.awaitTermination(STARTUP_TIMEOUT, TimeUnit.MILLISECONDS));
        launch.closed = true;
    }

    private static <T> T field(Instrumentation instrumentation, Activity activity, String name, Class<T> type) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                Field field = MainActivity.class.getDeclaredField(name);
                field.setAccessible(true);
                result.set(type.cast(field.get(activity)));
            } catch (Throwable failure) {
                error.set(failure);
            }
        });
        if (error.get() != null) throw new AssertionError("Cannot inspect Activity field " + name, error.get());
        return result.get();
    }

    private static UiObject2 await(UiDevice device, BySelector selector) {
        UiObject2 result = device.wait(Until.findObject(selector), UI_TIMEOUT);
        assertNotNull("Missing UI element: " + selector, result);
        return result;
    }

    private static void awaitEnabled(UiDevice device, BySelector selector, long timeout, String message) {
        assertNotNull(message, device.wait(Until.findObject(selector.enabled(true)), timeout));
    }

    private static void eventually(String message, long timeout, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        }
        fail(message);
    }

    private static void writeReport(File file, JSONObject report) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }
}
