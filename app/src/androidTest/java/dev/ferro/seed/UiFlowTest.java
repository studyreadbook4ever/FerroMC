package dev.ferro.seed;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** UI and storage integration only. Generation and successful NPU inference are not asserted here. */
@RunWith(AndroidJUnit4.class)
public final class UiFlowTest {
    private static final long TIMEOUT = 12_000;
    private Instrumentation instrumentation;
    private Context context;
    private UiDevice device;
    private HistoryStore store;
    private File screenshots;

    @Test public void historyLikesPlaybackAndDeletionFlow() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        device = UiDevice.getInstance(instrumentation);
        store = HistoryStore.shared(context);
        assertTrue("This UI fixture needs one free recent-history slot to preserve user records.",
                store.list().stream().filter(r -> !r.liked).count() < HistoryStore.MAX_RECENT_RECORDS);
        File external = context.getExternalFilesDir(null);
        assertNotNull("The app needs its external files directory for UI evidence.", external);
        screenshots = new File(external, "ui-test-screenshots");
        assertTrue(screenshots.isDirectory() || screenshots.mkdirs());

        // Preserve all existing user records. Only this run's unique IDs are removed in finally.
        String prefix = "ui-flow-" + UUID.randomUUID();
        List<String> seededIds = new ArrayList<>();
        Activity activity = null;
        long newest = System.currentTimeMillis();
        for (NoteRecord value : store.list()) newest = Math.max(newest, value.createdAt);
        assertTrue("The fixture timestamp must not overflow.", newest < Long.MAX_VALUE - 100);
        String recentId = prefix + "-recent";
        String newestLikedId = prefix + "-liked-19";
        try {
            for (int i = 0; i < 20; i++) {
                String id = prefix + "-liked-" + i;
                store.add(fixture(id, newest + i + 1, true));
                seededIds.add(id);
            }
            store.add(fixture(recentId, newest + 30, false));
            seededIds.add(recentId);

            Intent launch = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = instrumentation.startActivitySync(launch);
            UiObject2 generate = await(By.desc("음 생성"));
            UiObject2 replay = await(By.desc("재생"));
            int minimumButtonHeight = dp(64);
            assertTrue("Generate is a large central action.",
                    generate.getVisibleBounds().height() >= minimumButtonHeight);
            assertTrue("Playback is a large central action.",
                    replay.getVisibleBounds().height() >= minimumButtonHeight);
            assertTrue("Persisted notes are playable even if NPU setup is unavailable.", replay.isEnabled());
            screenshot("01-home.png");

            await(By.desc("설정")).click();
            await(By.textContains("정해진 코드 진행 없이"));
            await(By.text("재생 음량"));
            screenshot("02-settings.png");
            await(By.desc("처음 화면")).click();
            await(By.desc("과거 생성기록 보기")).click();
            await(By.desc("최근 생성 시작"));
            await(By.desc("기록 재생 " + recentId));
            eventually("The recent section must be anchored at the top, even with only one new record.", () -> {
                UiObject2 list = device.findObject(By.desc("생성 기록 목록"));
                UiObject2 recent = device.findObject(By.desc("최근 생성 시작"));
                return list != null && recent != null
                        && Math.abs(recent.getVisibleBounds().top - list.getVisibleBounds().top) <= dp(24);
            });
            for (int i = 0; i < 20; i++) {
                assertFalse("Favorite cards must initially remain above the recent viewport.",
                        visible(By.desc("기록 재생 " + prefix + "-liked-" + i)));
            }
            screenshot("03-history-recent-anchor.png");

            await(By.textStartsWith("♥ 좋아요 ")).click();
            await(By.desc("기록 재생 " + newestLikedId));
            screenshot("04-history-favorites-reachable.png");
            await(By.textStartsWith("최근 생성 ")).click();
            await(By.desc("기록 좋아요 " + recentId)).click();
            eventually("The favorite must be persisted.", () -> {
                NoteRecord value = store.get(recentId);
                return value != null && value.liked;
            });
            assertEquals("The newest favorite is first in the liked group.",
                    recentId, store.sortedHistory().get(0).id);
            await(By.desc("기록 내보내기 " + recentId));
            await(By.textStartsWith("♥ 좋아요 ")).click();
            await(By.desc("기록 내보내기 " + recentId));
            screenshot("05-record-liked.png");

            await(By.desc("기록 재생 " + recentId)).click();
            await(By.desc("처음 화면")).click();
            UiObject2 playback = await(By.desc("재생"));
            assertTrue(playback.isEnabled());
            playback.click();
            device.waitForIdle();
            // Audio device audibility is not asserted. The accessible playback control must recover.
            eventually("Playback must finish and return to the replay action.", () -> {
                UiObject2 button = device.findObject(By.desc("재생"));
                return button != null && button.isEnabled();
            });
            screenshot("06-playback-returned.png");

            await(By.desc("과거 생성기록 보기")).click();
            await(By.textStartsWith("♥ 좋아요 ")).click();
            await(By.desc("기록 삭제 " + recentId)).click();
            await(By.text("이 소리를 삭제할까요?"));
            UiObject2 confirmation = await(By.res("android", "button1"));
            assertEquals("삭제", confirmation.getText());
            confirmation.click();
            eventually("Only the selected test record must be removed.", () -> store.get(recentId) == null);
            HistoryStore disk = new HistoryStore(new File(context.getFilesDir(), "generated-history.json"));
            assertNull("Deletion must survive reloading the actual persisted file.", disk.get(recentId));
            assertNotNull("Other generated test records must remain intact.", disk.get(newestLikedId));
            await(By.desc("최근 생성 시작"));
            screenshot("07-record-deleted.png");
        } finally {
            // Finish this activity before removing fixtures, so no screen keeps a deleted test selection.
            if (activity != null) {
                Activity launched = activity;
                instrumentation.runOnMainSync(launched::finish);
                instrumentation.waitForIdleSync();
            }
            Exception cleanupFailure = null;
            for (String id : seededIds) {
                try { store.delete(id); }
                catch (Exception error) { cleanupFailure = error; }
            }
            if (cleanupFailure != null) throw cleanupFailure;
        }
    }

    private NoteRecord fixture(String id, long time, boolean liked) {
        return new NoteRecord(id, time, new int[]{50, 61, 70, 83}, new int[]{96, 77, 89, 101},
                liked, time, "ui-test-fixture");
    }

    private UiObject2 await(BySelector selector) {
        UiObject2 found = device.wait(Until.findObject(selector), TIMEOUT);
        assertNotNull("Missing UI element: " + selector, found);
        return found;
    }

    private void eventually(String message, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(40);
        }
        assertTrue(message, condition.getAsBoolean());
    }

    private boolean visible(BySelector selector) {
        UiObject2 object = device.findObject(selector);
        if (object == null) return false;
        Rect bounds = object.getVisibleBounds();
        return !bounds.isEmpty() && bounds.intersect(0, 0, device.getDisplayWidth(), device.getDisplayHeight());
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void screenshot(String name) {
        File target = new File(screenshots, name);
        assertTrue("Could not save UI evidence: " + target, device.takeScreenshot(target));
        Log.i("SoriUiTest", "Screenshot: " + target.getAbsolutePath());
    }
}
