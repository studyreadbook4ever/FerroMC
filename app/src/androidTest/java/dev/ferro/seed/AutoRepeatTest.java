package dev.ferro.seed;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual HTP and AudioTrack acceptance; only history persistence is redirected to a cache fixture. */
@RunWith(AndroidJUnit4.class)
public final class AutoRepeatTest {
    private static final long STARTUP_TIMEOUT = 60_000;
    private static final long ACTION_TIMEOUT = 30_000;
    private static final long NEXT_CYCLE_OBSERVATION = 6_200;

    @Test public void capturesPressedSoundAndStopsRepeatAcrossCallbacksAndNavigation() throws Throwable {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        UiDevice device = UiDevice.getInstance(instrumentation);
        boolean fullHundred = "true".equalsIgnoreCase(InstrumentationRegistry.getArguments()
                .getString("ferromc_full_repeat_100", "false"));
        JSONObject report = new JSONObject().put("schema_version", 1).put("status", "running")
                .put("run_id", InstrumentationRegistry.getArguments().getString("sori_validation_run_id", "manual"))
                .put("full_100_requested", fullHundred).put("full_100_verified", false)
                .put("successful_npu_mock_used", false).put("physical_audio_audibility_asserted", false)
                .put("started_at_epoch_ms", System.currentTimeMillis());
        File reportFile = new File(context.getFilesDir(), "auto-repeat-ui.json");
        writeReport(reportFile, report);
        Isolation fixture = null;
        Throwable primaryFailure = null;
        List<Gate> gates = new ArrayList<>();
        try {
            requirePhysicalS24Ultra();
            fixture = new Isolation(instrumentation, context);
            NoteRecord a = record("pressed-a", 2, new int[]{48, 63, 76});
            NoteRecord b = record("visible-b", 1, new int[]{53, 68, 81});
            fixture.store.add(a);
            fixture.store.add(b);
            fixture.launch();
            Isolation app = fixture;
            assertNotNull("Actual HTP initialization must enable generation.", device.wait(
                    Until.findObject(By.desc("음 생성").enabled(true)), STARTUP_TIMEOUT));
            assertSame("Activity must use the isolated history from its initial creation.", fixture.store,
                    main(instrumentation, () -> value(app.activity, "store", HistoryStore.class)));
            JSONObject before = diagnostics(context);
            assertHtp(before);
            report.put("initialization_diagnostics", before);

            // Keep a real DOWN gesture pending across the same UI update used by generation completion.
            Button heart = main(instrumentation, () -> value(app.activity, "homeLikeButton", Button.class));
            long down = SystemClock.uptimeMillis();
            main(instrumentation, () -> {
                touch(heart, MotionEvent.ACTION_DOWN, down);
                set(app.activity, "current", b.copy());
                invoke(app.activity, "showHome");
                assertSame("Refreshing a result must preserve the pressed like View.", heart,
                        value(app.activity, "homeLikeButton", Button.class));
                touch(heart, MotionEvent.ACTION_UP, down);
                return null;
            });
            eventually("The DOWN record must receive the like, not its replacement.", ACTION_TIMEOUT,
                    () -> app.store.get(a.id).liked);
            assertFalse("Replacement B must remain unliked after the A gesture.", app.store.get(b.id).liked);
            assertEquals(b.id, main(instrumentation, () -> value(app.activity, "current", NoteRecord.class)).id);
            report.put("pressed_record_identity_preserved", true).put("pressed_button_identity_preserved", true);

            // A busy NPU queue must not postpone five quick like gestures for B.
            Gate inferenceGate = new Gate(fixture.worker);
            gates.add(inferenceGate);
            inferenceGate.awaitEntered();
            main(instrumentation, () -> {
                for (int i = 0; i < 5; i++) {
                    long time = SystemClock.uptimeMillis();
                    touch(heart, MotionEvent.ACTION_DOWN, time);
                    touch(heart, MotionEvent.ACTION_UP, time);
                }
                return null;
            });
            instrumentation.waitForIdleSync();
            drain(fixture.historyWorker);
            instrumentation.waitForIdleSync();
            assertTrue("Five gestures must apply five serialized toggles to B.", fixture.store.get(b.id).liked);
            assertTrue("A's earlier favorite must be preserved.", fixture.store.get(a.id).liked);
            assertEquals("Likes must complete while the NPU queue is still blocked.", 1, inferenceGate.release.getCount());
            assertSame(heart, main(instrumentation, () -> value(app.activity, "homeLikeButton", Button.class)));
            inferenceGate.close();
            report.put("rapid_likes_independent_of_inference", true);

            Set<String> originalIds = new HashSet<>();
            for (NoteRecord item : fixture.store.list()) originalIds.add(item.id);
            JSONObject repeatBefore = diagnostics(context);
            click(device, "100회 재생");
            AtomicReference<Boolean> audioAdvanced = new AtomicReference<>(false);
            long fullTimeout = fullHundred ? 12 * 60_000L : 45_000L;
            eventually("Actual repeat must generate, play, and reach its requested cycle count.", fullTimeout, () -> {
                if (audioHeadAdvanced(app)) audioAdvanced.set(true);
                int count = generated(app.store, originalIds).size();
                return fullHundred ? count == 100 && !repeatActive(app) : count >= 3;
            });
            List<NoteRecord> generated = generated(fixture.store, originalIds);
            if (fullHundred) {
                assertEquals(100, generated.size());
                main(instrumentation, () -> {
                    RepeatSession session = value(app.activity, "repeatSession", RepeatSession.class);
                    assertEquals("All one hundred cycles must finish naturally, without cancellation or failure.",
                            RepeatSession.Phase.FINISHED, session.phase());
                    assertEquals(100, session.cycle());
                    assertEquals(3, session.playNumber());
                    return null;
                });
                JSONObject completed = diagnostics(context);
                assertHtp(completed);
                assertEquals("Every completed cycle needs four actual pitch passes.", 400,
                        completed.getLong("inference_count") - repeatBefore.getLong("inference_count"));
                int soundPasses = 0;
                for (NoteRecord item : generated) soundPasses += item.pitches.length;
                assertEquals(soundPasses, completed.getLong("sound_inference_count")
                        - repeatBefore.getLong("sound_inference_count"));
                report.put("full_100_verified", true).put("full_100_pitch_calls", 400)
                        .put("full_100_sound_calls", soundPasses);
            } else {
                click(device, "반복 중단");
            }
            drainBoth(fixture);
            assertTrue("At least one actual AudioTrack must advance its playback head.", audioAdvanced.get());
            report.put("actual_generated_cycles_observed", generated.size())
                    .put("actual_audio_playback_head_advanced", true);
            JSONArray sampleIds = new JSONArray();
            for (NoteRecord item : generated) {
                UUID.fromString(item.id);
                assertRecord(item, new HistoryStore(fixture.file).get(item.id));
                sampleIds.put(item.id);
            }
            report.put("generated_fixture_ids", sampleIds);
            assertQuietAfterStop(fixture, context);
            report.put("stop_cancels_delayed_callbacks", true);

            // Stop while generation is queued but has not entered the real NPU.
            JSONObject beforeQueuedStop = diagnostics(context);
            int recordsBeforeQueuedStop = fixture.store.list().size();
            Gate queuedGate = new Gate(fixture.worker);
            gates.add(queuedGate);
            queuedGate.awaitEntered();
            click(device, "100회 재생");
            eventually("A repeat generation must be pending behind the NPU gate.", ACTION_TIMEOUT,
                    () -> main(instrumentation, () -> value(app.activity, "generating", Boolean.class)));
            click(device, "반복 중단");
            queuedGate.close();
            drainBoth(fixture);
            assertEquals(recordsBeforeQueuedStop, fixture.store.list().size());
            assertCountsEqual(beforeQueuedStop, diagnostics(context));
            assertFalse(repeatActive(fixture));
            assertFalse(main(instrumentation, () -> value(app.activity, "playing", Boolean.class)));
            report.put("queued_generation_cancelled_before_inference", true);

            // Finish actual HTP inference while persistence is held, then stop before its late callback.
            JSONObject beforeLate = diagnostics(context);
            Gate historyGate = new Gate(fixture.historyWorker);
            gates.add(historyGate);
            historyGate.awaitEntered();
            click(device, "100회 재생");
            eventually("The genuine pitch and sound graphs must finish behind the storage gate.", ACTION_TIMEOUT, () -> {
                JSONObject now = diagnostics(context);
                return now.getLong("inference_count") - beforeLate.getLong("inference_count") == 4
                        && now.getLong("sound_inference_count") > beforeLate.getLong("sound_inference_count");
            });
            drain(fixture.worker);
            click(device, "반복 중단");
            historyGate.close();
            drainBoth(fixture);
            assertQuietAfterStop(fixture, context);
            report.put("late_storage_completion_cannot_restart_playback", true);

            // Leaving home cancels a queued repeat without changing the destination page.
            Gate navigationGate = new Gate(fixture.worker);
            gates.add(navigationGate);
            navigationGate.awaitEntered();
            JSONObject beforeNavigation = diagnostics(context);
            click(device, "100회 재생");
            click(device, "설정");
            eventually("Settings navigation must stop the active repeat.", ACTION_TIMEOUT,
                    () -> !repeatActive(app));
            navigationGate.close();
            drainBoth(fixture);
            assertEquals("settings", main(instrumentation, () -> value(app.activity, "page", String.class)));
            assertCountsEqual(beforeNavigation, diagnostics(context));
            assertFalse(main(instrumentation, () -> value(app.activity, "playing", Boolean.class)));
            click(device, "처음 화면");
            report.put("navigation_cancels_repeat", true);

            // Android onStop, not a test callback, must also cancel the queued cycle.
            Gate backgroundGate = new Gate(fixture.worker);
            gates.add(backgroundGate);
            backgroundGate.awaitEntered();
            JSONObject beforeBackground = diagnostics(context);
            click(device, "100회 재생");
            device.pressHome();
            eventually("Moving the real Activity to the background must stop repeat.", ACTION_TIMEOUT,
                    () -> !repeatActive(app));
            backgroundGate.close();
            drainBoth(fixture);
            assertCountsEqual(beforeBackground, diagnostics(context));
            assertFalse(main(instrumentation, () -> value(app.activity, "playing", Boolean.class)));
            report.put("background_cancels_repeat", true).put("history_isolated_from_user_data", true)
                    .put("final_npu_diagnostics", diagnostics(context)).put("status", "passed");
        } catch (Throwable failure) {
            primaryFailure = failure;
            report.put("status", "failed").put("error", failure.getClass().getName() + ": " + failure.getMessage());
            throw failure;
        } finally {
            for (Gate gate : gates) gate.close();
            Throwable cleanupFailure = null;
            if (fixture != null) {
                try { fixture.close(); } catch (Throwable failure) { cleanupFailure = failure; }
            }
            if (cleanupFailure != null) report.put("status", "failed").put("cleanup_error", cleanupFailure.toString());
            report.put("finished_at_epoch_ms", System.currentTimeMillis());
            try { writeReport(reportFile, report); } catch (Throwable failure) {
                if (cleanupFailure == null) cleanupFailure = failure; else cleanupFailure.addSuppressed(failure);
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure); else throw cleanupFailure;
            }
        }
    }

    private static void assertQuietAfterStop(Isolation app, Context context) throws Exception {
        JSONObject before = diagnostics(context);
        int size = app.store.list().size();
        long end = SystemClock.uptimeMillis() + NEXT_CYCLE_OBSERVATION;
        while (SystemClock.uptimeMillis() < end) {
            assertFalse("A stopped repeat must remain inactive.", repeatActive(app));
            assertFalse("Stale completion must not restart playback.", main(app.instrumentation,
                    () -> value(app.activity, "playing", Boolean.class)));
            assertCountsEqual(before, diagnostics(context));
            assertEquals("A stopped repeat must not add another result.", size, app.store.list().size());
            SystemClock.sleep(80);
        }
    }

    private static boolean repeatActive(Isolation app) throws Exception {
        return main(app.instrumentation, () -> value(app.activity, "repeatSession", RepeatSession.class).isRunning());
    }

    private static boolean audioHeadAdvanced(Isolation app) throws Exception {
        TonePlayer player = main(app.instrumentation, () -> value(app.activity, "player", TonePlayer.class));
        synchronized (player) {
            AudioTrack active = value(player, "active", AudioTrack.class);
            return active != null && active.getPlayState() == AudioTrack.PLAYSTATE_PLAYING
                    && active.getPlaybackHeadPosition() > 0;
        }
    }

    private static List<NoteRecord> generated(HistoryStore store, Set<String> originals) {
        List<NoteRecord> result = new ArrayList<>();
        for (NoteRecord record : store.list()) if (!originals.contains(record.id)) result.add(record);
        return result;
    }

    private static void assertCountsEqual(JSONObject expected, JSONObject actual) throws Exception {
        assertEquals("No additional pitch inference after cancellation.", expected.getLong("inference_count"),
                actual.getLong("inference_count"));
        assertEquals("No additional sound inference after cancellation.", expected.getLong("sound_inference_count"),
                actual.getLong("sound_inference_count"));
    }

    private static void assertRecord(NoteRecord expected, NoteRecord actual) {
        assertNotNull(actual);
        assertEquals(expected.id, actual.id);
        assertEquals(expected.createdAt, actual.createdAt);
        assertEquals(expected.seed, actual.seed);
        assertEquals(expected.liked, actual.liked);
        assertEquals(expected.modelVersion, actual.modelVersion);
        assertArrayEquals(expected.pitches, actual.pitches);
        assertArrayEquals(expected.velocities, actual.velocities);
        assertArrayEquals(expected.tones, actual.tones);
        assertArrayEquals(expected.decays, actual.decays);
        assertArrayEquals(expected.drives, actual.drives);
    }

    private static NoteRecord record(String id, long createdAt, int[] notes) {
        return new NoteRecord(id, createdAt, notes, new int[]{68, 80, 95},
                new int[]{20, 55, 90}, new int[]{40, 60, 80}, new int[]{10, 35, 70}, false, createdAt, "repeat-ui-fixture");
    }

    private static final class Isolation implements AutoCloseable {
        final Instrumentation instrumentation;
        final Context context;
        final File directory, file;
        final HistoryStore store;
        final Object previousShared;
        final Field shared;
        Activity activity;
        ExecutorService worker, historyWorker;

        Isolation(Instrumentation instrumentation, Context context) throws Exception {
            this.instrumentation = instrumentation;
            this.context = context;
            directory = new File(context.getCacheDir(), "auto-repeat-test-" + UUID.randomUUID());
            assertTrue(directory.mkdir());
            file = new File(directory, "history.json");
            store = new HistoryStore(file);
            shared = HistoryStore.class.getDeclaredField("sharedInstance");
            shared.setAccessible(true);
            synchronized (HistoryStore.class) {
                previousShared = shared.get(null);
                shared.set(null, store);
            }
        }

        void launch() throws Exception {
            activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            worker = main(instrumentation, () -> value(activity, "worker", ExecutorService.class));
            historyWorker = main(instrumentation, () -> value(activity, "historyWorker", ExecutorService.class));
        }

        @Override public void close() throws Exception {
            try {
                if (activity != null) {
                    main(instrumentation, () -> { activity.finish(); return null; });
                    instrumentation.waitForIdleSync();
                }
                if (worker != null) assertTrue("The NPU queue must finish before fixture removal.",
                        worker.awaitTermination(STARTUP_TIMEOUT, TimeUnit.MILLISECONDS));
                if (historyWorker != null) assertTrue("The history queue must finish before fixture removal.",
                        historyWorker.awaitTermination(STARTUP_TIMEOUT, TimeUnit.MILLISECONDS));
                deleteFixture(directory);
            } finally {
                synchronized (HistoryStore.class) { shared.set(null, previousShared); }
            }
        }
    }

    private static final class Gate implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Gate(ExecutorService executor) {
            executor.execute(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
        }
        void awaitEntered() throws Exception {
            assertTrue("The actual serial queue gate must start.", entered.await(STARTUP_TIMEOUT, TimeUnit.MILLISECONDS));
        }
        @Override public void close() { release.countDown(); }
    }

    private static void drainBoth(Isolation fixture) throws Exception {
        // Drain posted main-thread handoffs as well as direct worker-to-storage handoffs.
        for (int i = 0; i < 2; i++) {
            drain(fixture.worker);
            drain(fixture.historyWorker);
            fixture.instrumentation.waitForIdleSync();
        }
    }

    private static void drain(ExecutorService executor) throws Exception {
        CountDownLatch marker = new CountDownLatch(1);
        executor.execute(marker::countDown);
        assertTrue("The real executor must finish previously queued work.",
                marker.await(STARTUP_TIMEOUT, TimeUnit.MILLISECONDS));
    }

    private static void touch(View view, int action, long down) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                view.getWidth() / 2f, view.getHeight() / 2f, 0);
        try { assertTrue("The visible like button must handle the touch.", view.dispatchTouchEvent(event)); }
        finally { event.recycle(); }
    }

    private static void click(UiDevice device, String description) {
        assertNotNull("Missing enabled UI control: " + description,
                device.wait(Until.findObject(By.desc(description).enabled(true)), ACTION_TIMEOUT));
        device.findObject(By.desc(description)).click();
    }

    private interface CheckedSupplier<T> { T get() throws Exception; }
    private static <T> T main(Instrumentation instrumentation, CheckedSupplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(action.get()); } catch (Throwable problem) { failure.set(problem); }
        });
        if (failure.get() instanceof Exception) throw (Exception) failure.get();
        if (failure.get() instanceof Error) throw (Error) failure.get();
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    private static <T> T value(Object owner, String name, Class<T> type) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void invoke(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(owner);
    }

    private static void eventually(String message, long timeout, CheckedSupplier<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.get()) return;
            SystemClock.sleep(40);
        }
        assertTrue(message, condition.get());
    }

    private static JSONObject diagnostics(Context context) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(),
                "npu-diagnostics.json").toPath()), StandardCharsets.UTF_8));
    }

    private static void assertHtp(JSONObject evidence) throws Exception {
        assertEquals("htp_verified", evidence.getString("status"));
        assertTrue(evidence.getBoolean("cpu_graph_fallback_disabled"));
        assertFalse(evidence.getBoolean("io_quantization_cpu_offload"));
        assertTrue(evidence.getInt("profile_qnn_node_events") > 0);
        assertEquals(0, evidence.getInt("profile_non_qnn_node_events"));
        assertTrue(evidence.getInt("sound_profile_qnn_node_events") > 0);
        assertEquals(0, evidence.getInt("sound_profile_non_qnn_node_events"));
    }

    private static void requirePhysicalS24Ultra() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        assertFalse("Real S24 Ultra is required; emulator results cannot prove this feature.",
                hardware.contains("ranchu") || hardware.contains("goldfish")
                        || Build.FINGERPRINT.toLowerCase(Locale.ROOT).startsWith("generic")
                        || Build.PRODUCT.toLowerCase(Locale.ROOT).contains("sdk"));
        assertEquals("samsung", Build.MANUFACTURER.toLowerCase(Locale.ROOT));
        assertTrue(Build.MODEL.toUpperCase(Locale.ROOT).startsWith("SM-S928"));
    }

    private static void writeReport(File file, JSONObject report) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
    }

    private static void deleteFixture(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot inspect isolated fixture");
            for (File child : children) deleteFixture(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Cannot delete isolated fixture: " + file.getName());
    }
}
