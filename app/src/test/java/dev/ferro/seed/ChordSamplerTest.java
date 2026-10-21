package dev.ferro.seed;

import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ChordSamplerTest {
    private static float[] flat(float[] input) { return new float[input.length]; }

    @Test public void everyInferenceUsesFixedBatchAndExactlyOneMaskedVoice() throws Exception {
        AtomicInteger passes = new AtomicInteger();
        ChordSampler.Inference inspect = context -> {
            int maskedVoice = passes.getAndIncrement();
            assertEquals(16 * 4 * 64, context.length);
            assertTrue(maskedVoice < 4);
            for (int batch = 0; batch < 16; batch++) {
                boolean[] visible = new boolean[64];
                for (int voice = 0; voice < 4; voice++) {
                    int nonzero = 0;
                    for (int pitch = 0; pitch < 64; pitch++) {
                        float value = context[batch * 256 + voice * 64 + pitch];
                        assertTrue(value == 0f || value == 1f);
                        if (value == 1f) {
                            nonzero++;
                            assertTrue(pitch + 36 >= 48 && pitch + 36 <= 83);
                            assertFalse("Visible pitches must be distinct", visible[pitch]);
                            visible[pitch] = true;
                        }
                    }
                    assertEquals(voice == maskedVoice ? 0 : 1, nonzero);
                }
            }
            return flat(context);
        };
        int[] chord = ChordSampler.generate(inspect, 4312L, 4, .55, 48, 83);
        assertEquals(4, passes.get());
        assertChord(chord, 4, 48, 83);
    }

    @Test public void countRangeUniquenessAndSortHoldAtSupportedBounds() throws Exception {
        int[][] ranges = {{36, 48}, {48, 83}, {87, 99}, {36, 99}};
        for (int[] range : ranges) for (int count = 2; count <= 4; count++) {
            for (int seed = 0; seed < 12; seed++) {
                int[] chord = ChordSampler.generate(ChordSamplerTest::flat, seed, count,
                        seed % 2, range[0], range[1]);
                assertChord(chord, count, range[0], range[1]);
            }
        }
    }

    @Test public void reducedCountsDoNotAlwaysKeepTheLowestOrHighestRanks() throws Exception {
        for (int count : new int[] {2, 3}) {
            boolean[] everKept = new boolean[4];
            boolean[] everOmitted = new boolean[4];
            Set<Integer> subsets = new HashSet<>();
            for (long seed = 0; seed < 64; seed++) {
                int[] full = ChordSampler.generate(ChordSamplerTest::flat, seed, 4, .55, 48, 83);
                int[] reduced = ChordSampler.generate(ChordSamplerTest::flat, seed, count, .55, 48, 83);
                assertChord(reduced, count, 48, 83);
                int mask = 0;
                for (int pitch : reduced) {
                    int rank = Arrays.binarySearch(full, pitch);
                    assertTrue("Reducing count must select from the same seed's four-note chord", rank >= 0);
                    mask |= 1 << rank;
                }
                subsets.add(mask);
                for (int rank = 0; rank < 4; rank++) {
                    if ((mask & (1 << rank)) != 0) everKept[rank] = true;
                    else everOmitted[rank] = true;
                }
            }
            assertTrue("Reduced counts must explore multiple rank combinations", subsets.size() >= 3);
            for (int rank = 0; rank < 4; rank++) {
                assertTrue("Every rank must sometimes remain for count " + count, everKept[rank]);
                assertTrue("No rank, including either extreme, may be forced for count " + count, everOmitted[rank]);
            }
        }
    }

    @Test public void sameSeedAndModelGiveSameResult() throws Exception {
        ChordSampler.Inference model = context -> {
            float[] logits = new float[context.length];
            for (int i = 0; i < logits.length; i++) logits[i] = (float) Math.sin((i % 64) * .41);
            return logits;
        };
        assertArrayEquals(ChordSampler.generate(model, 987654321L, 4, .55, 48, 83),
                ChordSampler.generate(model, 987654321L, 4, .55, 48, 83));
    }

    @Test public void javaSamplerMatchesIndependentValidationReplay() throws Exception {
        // These were produced by tools/model_validate_app_sampler.py's independent
        // Java-Random-compatible replay, including 16 candidates and four passes.
        assertArrayEquals(new int[] {49, 56, 61, 66},
                ChordSampler.generate(ChordSamplerTest::flat, 202610210000L, 4, .55, 48, 83));
        assertArrayEquals(new int[] {52, 54, 67, 76},
                ChordSampler.generate(ChordSamplerTest::flat, 202610210001L, 4, .55, 48, 83));
    }

    @Test public void neuralLogitsActuallyChangeSampling() throws Exception {
        for (int preferred : new int[] {48, 83}) {
            ChordSampler.Inference concentrated = context -> {
                float[] logits = new float[context.length];
                Arrays.fill(logits, -1000f);
                for (int batch = 0; batch < 16; batch++) for (int voice = 0; voice < 4; voice++)
                    logits[batch * 256 + voice * 64 + preferred - 36] = 1000f;
                return logits;
            };
            int[] result = ChordSampler.generate(concentrated, 7345L, 4, 0, 48, 83);
            assertTrue("The sharply preferred neural pitch must survive", Arrays.stream(result).anyMatch(p -> p == preferred));
        }
    }

    @Test public void inferenceFailureAbortsWithoutRetryOrRandomFallback() {
        AtomicInteger calls = new AtomicInteger();
        Exception failure = new Exception("HTP deliberately unavailable");
        try {
            ChordSampler.generate(context -> {
                if (calls.incrementAndGet() == 2) throw failure;
                return flat(context);
            }, 17L, 4, .55, 48, 83);
            fail("Inference failure must propagate");
        } catch (Exception actual) {
            assertSame(failure, actual);
        }
        assertEquals("No retry after the failing model call", 2, calls.get());
    }

    @Test public void malformedNeuralOutputIsRejected() throws Exception {
        float[][] malformed = {null, new float[4095], new float[4097], new float[4096], new float[4096]};
        malformed[3][4000] = Float.NaN;
        malformed[4][2] = Float.POSITIVE_INFINITY;
        for (float[] bad : malformed) {
            AtomicInteger calls = new AtomicInteger();
            try {
                ChordSampler.generate(context -> { calls.incrementAndGet(); return bad; },
                        8L, 4, .55, 48, 83);
                fail("Invalid NPU result must not yield a chord");
            } catch (IllegalStateException expected) {
                assertEquals(1, calls.get());
            }
        }
    }

    @Test public void invalidSettingsDoNotInvokeInference() throws Exception {
        int[][] settings = {{1, 48, 83}, {5, 48, 83}, {4, 35, 83}, {4, 48, 100}, {4, 60, 71}};
        for (int[] setting : settings) {
            AtomicInteger calls = new AtomicInteger();
            try {
                ChordSampler.generate(context -> { calls.incrementAndGet(); return flat(context); },
                        10L, setting[0], .55, setting[1], setting[2]);
                fail("Invalid settings must fail before inference");
            } catch (IllegalArgumentException expected) {
                assertEquals(0, calls.get());
            }
        }
    }

    private static void assertChord(int[] chord, int count, int low, int high) {
        assertEquals(count, chord.length);
        for (int i = 0; i < chord.length; i++) {
            assertTrue(chord[i] >= low && chord[i] <= high);
            if (i > 0) assertTrue("Ascending order also proves uniqueness", chord[i - 1] < chord[i]);
        }
    }
}
