package dev.ferro.seed;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Real UI/storage race regression. Does not replace, bypass, or assert successful NPU inference. */
@RunWith(AndroidJUnit4.class)
public final class UiStateTest {
    private static final long UI_TIMEOUT = 12_000;
    private static final long WORKER_TIMEOUT = 30_000;

    @Test public void deletingCurrentThenReturningHomeRefreshesDisplayedSelection() throws Throwable {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        UiDevice device = UiDevice.getInstance(instrumentation);
        HistoryStore store = HistoryStore.shared(context);
        assertTrue("This UI fixture needs two free recent-history slots to preserve user records.",
                store.list().stream().filter(r -> !r.liked).count() <= HistoryStore.MAX_RECENT_RECORDS - 2);
        String prefix = "ui-state-" + UUID.randomUUID();
        String currentId = prefix + "-a";
        String remainingId = prefix + "-b";
        List<String> fixtureIds = new ArrayList<>();
        CountDownLatch gateEntered = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);
        AtomicReference<Throwable> gateFailure = new AtomicReference<>();
        Activity activity = null;
        ExecutorService worker = null;
        ExecutorService historyWorker = null;
        Throwable primaryFailure = null;

        try {
            long newest = System.currentTimeMillis();
            for (NoteRecord record : store.list()) newest = Math.max(newest, record.createdAt);
            assertTrue("Fixture timestamps must not overflow.", newest <= Long.MAX_VALUE - 3);
            NoteRecord a = new NoteRecord(currentId, newest + 2,
                    new int[] {49, 58, 66, 78}, new int[] {96, 96, 96, 96},
                    false, 1001L, "ui-state-fixture");
            NoteRecord b = new NoteRecord(remainingId, newest + 1,
                    new int[] {52, 61, 70, 81}, new int[] {96, 96, 96, 96},
                    false, 1002L, "ui-state-fixture");
            // Register each ID before writing it so even a partial setup is cleaned up.
            fixtureIds.add(remainingId);
            store.add(b);
            fixtureIds.add(currentId);
            store.add(a);

            activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(device, By.text(ChordSampler.describe(a.pitches)));
            assertEquals(currentId, current(instrumentation, activity).id);
            worker = fieldOnMain(instrumentation, activity, "worker", ExecutorService.class);
            historyWorker = fieldOnMain(instrumentation, activity, "historyWorker", ExecutorService.class);

            // Gate the actual storage queue independently of NPU initialization.
            // The main/UI thread and inference queue stay responsive.
            historyWorker.execute(() -> {
                gateEntered.countDown();
                try {
                    releaseGate.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    gateFailure.set(interrupted);
                }
            });
            assertTrue("The storage queue gate must start.",
                    gateEntered.await(WORKER_TIMEOUT, TimeUnit.MILLISECONDS));
            instrumentation.waitForIdleSync();

            await(device, By.desc("과거 생성기록 보기")).click();
            await(device, By.desc("기록 삭제 " + currentId)).click();
            await(device, By.text("이 소리를 삭제할까요?"));
            UiObject2 confirm = await(device, By.res("android", "button1"));
            assertEquals("삭제", confirm.getText());
            confirm.click();
            assertTrue("Confirmation must enqueue deletion and dismiss the dialog.",
                    device.wait(Until.gone(By.text("이 소리를 삭제할까요?")), UI_TIMEOUT));

            // Deletion is now definitely queued behind our gate. Navigate before it can commit.
            assertNull("The worker gate must not have been interrupted.", gateFailure.get());
            assertEquals(1L, releaseGate.getCount());
            assertNotNull("A must still exist while deletion is queued.", store.get(currentId));
            await(device, By.desc("처음 화면")).click();
            await(device, By.desc("과거 생성기록 보기"));
            await(device, By.text(ChordSampler.describe(a.pitches)));
            assertEquals("home", fieldOnMain(instrumentation, activity, "page", String.class));
            assertEquals("Navigation must not itself switch the current record.",
                    currentId, current(instrumentation, activity).id);

            // This marker is after deletion in the same FIFO queue. Waiting for it
            // ensures the deletion's UI callback was posted before we drain UI work.
            CountDownLatch deletionTaskFinished = new CountDownLatch(1);
            historyWorker.execute(deletionTaskFinished::countDown);
            releaseGate.countDown();
            assertTrue("The queued deletion task must finish after its gate is released.",
                    deletionTaskFinished.await(WORKER_TIMEOUT, TimeUnit.MILLISECONDS));
            eventually("The real queued deletion must commit.", () -> store.get(currentId) == null);
            instrumentation.waitForIdleSync();
            NoteRecord selected = current(instrumentation, activity);
            assertNotNull("B must replace the deleted current record.", selected);
            assertEquals(remainingId, selected.id);
            assertEquals("The user must remain on the home page.", "home",
                    fieldOnMain(instrumentation, activity, "page", String.class));
            await(device, By.text(ChordSampler.describe(selected.pitches)));
            assertNull("The deleted A title must not remain on screen while actions target B.",
                    device.findObject(By.text(ChordSampler.describe(a.pitches))));
            assertTrue("The remaining current record must still be playable.",
                    await(device, By.desc("재생")).isEnabled());

            HistoryStore disk = new HistoryStore(new File(context.getFilesDir(), "generated-history.json"));
            assertNull("A deletion must be durable, not only an in-memory UI change.", disk.get(currentId));
            assertNotNull("The other fixture must remain intact.", disk.get(remainingId));
            assertNull(gateFailure.get());
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            // Always release the worker before Activity destruction or fixture cleanup.
            releaseGate.countDown();
            List<Throwable> cleanupFailures = new ArrayList<>();
            if (activity != null) {
                Activity launched = activity;
                try {
                    instrumentation.runOnMainSync(launched::finish);
                    instrumentation.waitForIdleSync();
                } catch (Throwable failure) {
                    cleanupFailures.add(failure);
                }
            }
            if (worker != null) {
                try {
                    assertTrue("The Activity's worker must drain before removing test fixtures.",
                            worker.awaitTermination(WORKER_TIMEOUT, TimeUnit.MILLISECONDS));
                } catch (Throwable failure) {
                    cleanupFailures.add(failure);
                }
            }
            if (historyWorker != null) {
                try {
                    assertTrue("The Activity's history queue must drain before fixture cleanup.",
                            historyWorker.awaitTermination(WORKER_TIMEOUT, TimeUnit.MILLISECONDS));
                } catch (Throwable failure) {
                    cleanupFailures.add(failure);
                }
            }
            // Never clear the store or restore a stale snapshot: touch this run's IDs only.
            for (String fixtureId : fixtureIds) {
                try {
                    store.delete(fixtureId);
                } catch (Throwable failure) {
                    cleanupFailures.add(failure);
                }
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

    private static NoteRecord current(Instrumentation instrumentation, Activity activity) {
        return fieldOnMain(instrumentation, activity, "current", NoteRecord.class);
    }

    private static <T> T fieldOnMain(Instrumentation instrumentation, Activity activity,
                                    String name, Class<T> type) {
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
        if (error.get() != null) throw new AssertionError("Cannot inspect UI field " + name, error.get());
        return result.get();
    }

    private static UiObject2 await(UiDevice device, BySelector selector) {
        UiObject2 result = device.wait(Until.findObject(selector), UI_TIMEOUT);
        assertNotNull("Missing UI element: " + selector, result);
        return result;
    }

    private static void eventually(String message, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + UI_TIMEOUT;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(40);
        }
        assertTrue(message, condition.getAsBoolean());
    }
}
