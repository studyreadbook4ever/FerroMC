package dev.ferro.seed;

import java.util.Arrays;
import java.util.Random;

/** CPU sampling/postprocessing around a neural graph which MUST execute on HTP. */
public final class ChordSampler {
    public interface Inference { float[] infer(float[] context) throws Exception; }
    public static final int BATCH = 16, VOICES = 4, PITCHES = 64, LOWEST = 36;

    public static int[] generate(Inference inference, long seed, int count, double exploration,
                                 int lowPitch, int highPitch) throws Exception {
        if (count < 2 || count > 4 || lowPitch < 36 || highPitch > 99 || highPitch - lowPitch < 12)
            throw new IllegalArgumentException("생성 범위가 올바르지 않습니다.");
        Random random = new Random(seed);
        int[][] candidates = new int[BATCH][VOICES];
        for (int[] chord : candidates) {
            for (int v = 0; v < VOICES; v++) {
                int pitch;
                do { pitch = lowPitch + random.nextInt(highPitch - lowPitch + 1); }
                while (contains(chord, v, pitch));
                chord[v] = pitch;
            }
            Arrays.sort(chord);
        }
        for (int iteration = 0; iteration < 4; iteration++) {
            int voice = iteration;
            float[] context = new float[BATCH * VOICES * PITCHES];
            for (int b = 0; b < BATCH; b++) for (int v = 0; v < VOICES; v++)
                if (v != voice) context[b * 256 + v * 64 + candidates[b][v] - LOWEST] = 1;
            float[] logits = inference.infer(context);
            if (logits == null || logits.length != context.length) throw new IllegalStateException("NPU 출력 크기가 올바르지 않습니다.");
            for (float logit : logits) if (!Float.isFinite(logit)) throw new IllegalStateException("NPU 출력에 잘못된 값이 있습니다.");
            for (int b = 0; b < BATCH; b++) {
                int start = b * 256 + voice * 64;
                double temperature = 0.75 + Math.max(0, Math.min(1, exploration)) * 0.9;
                double max = -Double.MAX_VALUE;
                for (int p = lowPitch; p <= highPitch; p++)
                    if (!otherVoice(candidates[b], voice, p)) max = Math.max(max, logits[start + p - LOWEST] / temperature);
                double[] weights = new double[64];
                double total = 0;
                for (int p = lowPitch; p <= highPitch; p++) if (!otherVoice(candidates[b], voice, p)) {
                    // A small broad component preserves exploration without adding chord/scale rules.
                    weights[p - LOWEST] = Math.exp(logits[start + p - LOWEST] / temperature - max)
                            + 0.02 * exploration;
                    total += weights[p - LOWEST];
                }
                double draw = random.nextDouble() * total;
                int selected = -1;
                for (int p = lowPitch; p <= highPitch; p++) if (weights[p - LOWEST] > 0) {
                    selected = p;
                    draw -= weights[p - LOWEST];
                    if (draw <= 0) break;
                }
                if (selected < 0) throw new IllegalStateException("음높이 후보가 없습니다.");
                candidates[b][voice] = selected;
            }
        }
        // Select uniformly across the learned batch: do not keep only familiar/smooth extrema.
        int[] result = candidates[random.nextInt(BATCH)].clone();
        if(count<VOICES){
            // Smaller selections must not impose a preference for outer or inner voices.
            for(int i=0;i<count;i++){
                int chosen=i+random.nextInt(VOICES-i);
                int swap=result[i];result[i]=result[chosen];result[chosen]=swap;
            }
            result=Arrays.copyOf(result,count);
        }
        Arrays.sort(result);
        return result;
    }

    private static boolean contains(int[] a, int end, int p) { for (int i=0;i<end;i++) if (a[i]==p) return true; return false; }
    private static boolean otherVoice(int[] a, int except, int p) { for (int i=0;i<a.length;i++) if (i!=except && a[i]==p) return true; return false; }
    public static String pitchName(int pitch) {
        String[] names={"C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B"};
        return names[Math.floorMod(pitch,12)] + (pitch/12-1);
    }
    public static String describe(int[] pitches) {
        StringBuilder s=new StringBuilder();
        for(int p:pitches){if(s.length()>0)s.append(" · ");s.append(pitchName(p));}
        return s.toString();
    }
}
