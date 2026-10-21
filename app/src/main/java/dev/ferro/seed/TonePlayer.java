package dev.ferro.seed;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Ferro's Saw voice on the audio CPU; both learned generation graphs run on HTP. */
public final class TonePlayer implements AutoCloseable {
    public static final int SAMPLE_RATE = 48000;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AudioTrack active;
    private long generation;
    private boolean closed;

    public static short[] render(NoteRecord record, double volume) {
        NoteRecord safe = record.copy();
        double duration = 0;
        for (int decay : safe.decays) duration = Math.max(duration, .10 + .44 * decay / 100.0);
        // Preserve the causal DC-blocker's release after the final voice ends.
        float[] samples = renderFloat(safe, volume, (int) Math.ceil((duration + .25) * SAMPLE_RATE));
        short[] pcm = new short[samples.length];
        for (int i = 0; i < samples.length; i++) pcm[i] = (short) Math.round(clamp(samples[i], -1, 1) * 32767);
        return pcm;
    }

    /** Deterministic mono renderer, also compared against Ferro's actual C++ DSP in tests. */
    static float[] renderFloat(NoteRecord record, double volume, int frames) {
        NoteRecord safe = record.copy();
        if (!Double.isFinite(volume) || frames < 128 || frames > SAMPLE_RATE * 10)
            throw new IllegalArgumentException("재생 설정이 올바르지 않습니다.");
        double master = clamp(volume, 0, .8);
        float[] output = new float[frames];
        for (int n = 0; n < safe.pitches.length; n++) {
            SawVoice voice = new SawVoice(safe.pitches[n], safe.tones[n] / 100.0,
                    safe.decays[n] / 100.0, safe.drives[n] / 100.0);
            double level = safe.velocities[n] / 127.0 * .24;
            int length = Math.min(frames, (int) Math.ceil(voice.duration * SAMPLE_RATE));
            for (int i = 0; i < length; i++) output[i] += (float) (voice.next(i) * level);
        }
        Biquad dc = new Biquad();
        dc.highpass(10, SAMPLE_RATE);
        for (int i = 0; i < frames; i++) {
            double x = dc.next(output[i]) * master;
            double a = Math.abs(x), limited = a <= .82 ? a : .82 + .16 * Math.tanh((a - .82) / .16);
            double end = Math.min(1.0, (frames - i - 1) / 127.0);
            output[i] = (float) (Math.copySign(limited, x) * end);
        }
        return output;
    }

    public synchronized void play(NoteRecord record, double volume, Runnable finished,
                                  java.util.function.Consumer<String> failed) {
        if (closed) return;
        stop();
        long token = ++generation;
        executor.execute(() -> {
            AudioTrack track = null;
            try {
                short[] pcm = render(record, volume);
                synchronized (this) {
                    if (closed || token != generation) return;
                    track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
                    active = track;
                    int written=track.write(pcm, 0, pcm.length);
                    if(written!=pcm.length)throw new IllegalStateException("오디오 데이터를 준비하지 못했습니다: "+written);
                    track.play();
                }
                long deadline = android.os.SystemClock.uptimeMillis() + pcm.length * 1000L / SAMPLE_RATE + 100;
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    synchronized (this) { if (closed || token != generation) break; }
                    Thread.sleep(25);
                }
            } catch (Exception e) {
                android.util.Log.e("SoriAudio", "Playback failed", e);
                if(!(e instanceof InterruptedException) && failed!=null)failed.accept(e.getMessage());
            } finally {
                synchronized (this) {
                    if (track != null && active == track) {
                        try { track.stop(); } catch (IllegalStateException ignored) { }
                        track.release();
                        active = null;
                    }
                }
                if (finished != null) finished.run();
            }
        });
    }

    public synchronized void stop() {
        generation++;
        if (active != null) {
            try { active.stop(); } catch (IllegalStateException ignored) { }
            active.release();
            active = null;
        }
    }

    @Override public synchronized void close() { closed = true; stop(); executor.shutdownNow(); }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    // Ported from Ferro src/dsp.cpp at ea8a1451 (Unlicense): Saw voice, 2x
    // oversampling, polyBLEP oscillators, resonant filter, anti-alias and DC filters.
    private static final class SawVoice {
        final double tone, decay, drive, duration, increment;
        double phase;
        final Biquad anti1 = new Biquad(), anti2 = new Biquad();
        final ResonantFilter filter = new ResonantFilter();
        SawVoice(int pitch, double tone, double decay, double drive) {
            this.tone = tone; this.decay = decay; this.drive = drive;
            duration = .10 + .44 * decay;
            increment = Math.min(440 * Math.pow(2, (pitch - 69) / 12.0) / (SAMPLE_RATE * 2), .20);
            anti1.lowpass(14500, SAMPLE_RATE * 2, .5411961001);
            anti2.lowpass(14500, SAMPLE_RATE * 2, 1.3065629649);
        }
        double next(int frame) {
            double end = clamp((duration - frame / (double) SAMPLE_RATE) / .012, 0, 1);
            double result = 0;
            for (int k = 0; k < 2; k++) {
                double t = (frame + k / 2.0) / SAMPLE_RATE;
                phase += increment; phase -= Math.floor(phase);
                double saw = 2 * phase - 1 - polyBlep(phase, increment);
                double square = (phase < .5 ? 1 : -1) + polyBlep(phase, increment)
                        - polyBlep((phase + .5) % 1, increment);
                double raw = .79 * saw + .21 * square;
                double cutoff = 110 + tone * tone * 3200
                        + (700 + tone * 6500) * Math.exp(-t / (.022 + decay * .09));
                double signal = filter.next(Math.tanh(raw * (1 + drive * 2)), cutoff,
                        .59 + tone * .23, SAMPLE_RATE * 2);
                double envelope = (1 - Math.exp(-t / .0015)) * Math.exp(-t / (.035 + decay * .16));
                result = anti2.next(anti1.next(signal)) * .58 * envelope * end;
            }
            return result;
        }
        private static double polyBlep(double phase, double increment) {
            if (phase < increment) { double x = phase / increment; return x + x - x * x - 1; }
            if (phase > 1 - increment) { double x = (phase - 1) / increment; return x * x + x + x + 1; }
            return 0;
        }
    }

    private static final class ResonantFilter {
        double s1, s2;
        double next(double input, double cutoff, double resonance, double rate) {
            double g = Math.tan(Math.PI * clamp(cutoff, 25, rate * .20) / rate);
            double k = 2 - 1.86 * clamp(resonance, 0, 1);
            double a1 = 1 / (1 + g * (g + k)), a2 = g * a1, a3 = g * a2;
            double v3 = input - s2, v1 = a1 * s1 + a2 * v3, v2 = s2 + a2 * s1 + a3 * v3;
            s1 = 2 * v1 - s1; s2 = 2 * v2 - s2;
            return v2;
        }
    }

    private static final class Biquad {
        double b0 = 1, b1, b2, a1, a2, z1, z2;
        void lowpass(double cutoff, double rate, double q) {
            double w = 2 * Math.PI * clamp(cutoff, 10, rate * .45) / rate;
            double c = Math.cos(w), alpha = Math.sin(w) / (2 * q), inv = 1 / (1 + alpha);
            b0 = (1 - c) * .5 * inv; b1 = (1 - c) * inv; b2 = b0;
            a1 = -2 * c * inv; a2 = (1 - alpha) * inv;
        }
        void highpass(double cutoff, double rate) {
            double w = 2 * Math.PI * clamp(cutoff, 10, rate * .45) / rate;
            double c = Math.cos(w), alpha = Math.sin(w) / Math.sqrt(2), inv = 1 / (1 + alpha);
            b0 = (1 + c) * .5 * inv; b1 = -(1 + c) * inv; b2 = b0;
            a1 = -2 * c * inv; a2 = (1 - alpha) * inv;
        }
        double next(double input) {
            double out = b0 * input + z1;
            z1 = b1 * input - a1 * out + z2; z2 = b2 * input - a2 * out;
            return out;
        }
    }
}
