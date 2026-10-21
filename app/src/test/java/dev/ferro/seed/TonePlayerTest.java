package dev.ferro.seed;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Pure renderer checks; actual Ferro waveform comparison lives in verify_ferro_preview.py. */
public class TonePlayerTest {
    private static NoteRecord notes(int velocity, int tone, int decay, int drive) {
        return new NoteRecord("audio", 0, new int[]{57, 64, 70},
                new int[]{velocity, velocity, velocity}, new int[]{tone, tone, tone},
                new int[]{decay, decay, decay}, new int[]{drive, drive, drive}, false, 0, "test");
    }

    @Test public void previewIsAudibleBoundedAndEndsAtZero() {
        short[] pcm = TonePlayer.render(notes(127, 80, 80, 60), .8);
        assertTrue("Preview must contain a signal", rms(pcm) > 100);
        for (short sample : pcm) {
            assertNotEquals(Short.MIN_VALUE, sample);
            assertNotEquals(Short.MAX_VALUE, sample);
        }
        assertEquals(0, pcm[pcm.length - 1]);
    }

    @Test public void everyStoredSoundControlChangesThePreview() {
        short[] baseline = TonePlayer.render(notes(100, 50, 50, 50), .5);
        assertFalse(Arrays.equals(baseline, TonePlayer.render(notes(100, 10, 50, 50), .5)));
        assertFalse(Arrays.equals(baseline, TonePlayer.render(notes(100, 50, 50, 10), .5)));
        short[] shorter = TonePlayer.render(notes(100, 50, 0, 50), .5);
        short[] longer = TonePlayer.render(notes(100, 50, 100, 50), .5);
        assertEquals(16800, shorter.length);
        assertEquals(37920, longer.length);
        assertTrue("Long decay must retain more tail energy", tailEnergy(longer) > tailEnergy(shorter));
    }

    @Test public void velocityAndVolumeControlSignalLevel() {
        short[] strong = TonePlayer.render(notes(127, 50, 80, 20), .6);
        short[] soft = TonePlayer.render(notes(64, 50, 80, 20), .6);
        assertEquals(127.0 / 64, rms(strong) / rms(soft), .01);
        assertEquals(2, rms(strong) / rms(TonePlayer.render(notes(127, 50, 80, 20), .3)), .01);
        assertEquals(0.0, rms(TonePlayer.render(notes(127, 50, 80, 20), 0)), 0.0);
    }

    @Test public void extremeNotesAndControlsAreFiniteAndRepeatable() {
        NoteRecord record = new NoteRecord("edges", 0, new int[]{0, 43, 58, 127},
                new int[]{1, 96, 72, 127}, new int[]{0, 50, 100, 37},
                new int[]{100, 0, 80, 29}, new int[]{0, 100, 20, 67}, false, 0, "test");
        float[] pcm = TonePlayer.renderFloat(record, .7, 48000);
        for (float sample : pcm) assertTrue(Float.isFinite(sample) && Math.abs(sample) < 1);
        assertArrayEquals(TonePlayer.render(record, .7), TonePlayer.render(record, .7));
    }

    @Test public void invalidPlaybackSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TonePlayer.render(notes(100, 50, 80, 20), Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> TonePlayer.renderFloat(notes(100, 50, 80, 20), .5, 0));
    }

    private static double tailEnergy(short[] pcm) {
        double sum = 0;
        for (int i = 6000; i < pcm.length; i++) sum += (double) pcm[i] * pcm[i];
        return sum;
    }
    private static double rms(short[] pcm) {
        double total = 0;
        for (short sample : pcm) total += (double) sample * sample;
        return Math.sqrt(total / pcm.length);
    }
}
