package dev.ferro.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.media.AudioTrack;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real AudioTrack lifecycle checks; these cannot establish physical audibility. */
@RunWith(AndroidJUnit4.class)
public final class AudioPlaybackTest {
    private static final NoteRecord NOTES = new NoteRecord(
            "audio-instrumentation-only", 0L, new int[] {57, 64, 70},
            new int[] {90, 85, 80}, false, 0L, "audio-test");

    @Test public void actualAudioTrackAdvancesAndCompletesWithoutFailure() throws Exception {
        try (TonePlayer player = new TonePlayer()) {
            Playback result = play(player);
            awaitAdvancingTrack(player, result);
            awaitSuccess(result);
            assertReleased(player);
        }
    }

    @Test public void stoppedAudioTrackIsReleasedAndNextPlaybackSucceeds() throws Exception {
        try (TonePlayer player = new TonePlayer()) {
            Playback interrupted = play(player);
            awaitAdvancingTrack(player, interrupted);
            player.stop();
            assertReleased(player);
            awaitSuccess(interrupted);

            Playback replay = play(player);
            awaitAdvancingTrack(player, replay);
            awaitSuccess(replay);
            assertReleased(player);
        }
    }

    private static Playback play(TonePlayer player) {
        Playback result = new Playback();
        // The fixture exists only in memory; no user history or preferences change.
        player.play(NOTES, 0.15, () -> {
            result.completions.incrementAndGet();
            result.finished.countDown();
        }, error -> {
            result.failureMessage.set(String.valueOf(error));
            result.failures.incrementAndGet();
        });
        return result;
    }

    private static void awaitAdvancingTrack(TonePlayer player, Playback result) throws Exception {
        Field field = TonePlayer.class.getDeclaredField("active");
        field.setAccessible(true);
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < deadline) {
            // TonePlayer uses this same monitor when stopping/releasing the track.
            synchronized (player) {
                AudioTrack track = (AudioTrack) field.get(player);
                if (track != null && track.getState() == AudioTrack.STATE_INITIALIZED
                        && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING
                        && Integer.toUnsignedLong(track.getPlaybackHeadPosition()) > 0) {
                    return;
                }
            }
            if (result.finished.getCount() == 0) break;
            Thread.sleep(10);
        }
        assertEquals("Playback failed: " + result.failureMessage.get(), 0, result.failures.get());
        throw new AssertionError("A real initialized AudioTrack never advanced while playing");
    }

    private static void awaitSuccess(Playback result) throws InterruptedException {
        assertTrue("Playback completion callback did not arrive", result.finished.await(10, TimeUnit.SECONDS));
        assertEquals("Playback failed: " + result.failureMessage.get(), 0, result.failures.get());
        assertEquals("Playback must complete exactly once", 1, result.completions.get());
    }

    private static void assertReleased(TonePlayer player) throws Exception {
        Field field = TonePlayer.class.getDeclaredField("active");
        field.setAccessible(true);
        synchronized (player) {
            assertNull("Completed/stopped playback must release its active track", field.get(player));
        }
    }

    private static final class Playback {
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger completions = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicReference<String> failureMessage = new AtomicReference<>();
    }
}
