package dev.ferro.seed;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class SoundSamplerTest {
    private static float[] flat(float[] context){return new float[16*64];}

    @Test public void masksOnlyOneActiveVoiceAndKeepsItsPitch() throws Exception {
        for(int count=2;count<=4;count++) {
            int[] pitches=Arrays.copyOf(new int[]{36,57,81,99},count);
            AtomicInteger calls=new AtomicInteger();
            SoundSampler.Result result=SoundSampler.generate(input->{
                int missing=calls.getAndIncrement();assertEquals(16*32,input.length);
                for(int b=0;b<16;b++)for(int v=0;v<4;v++){
                    int start=b*32+v*8;
                    if(v>=pitches.length){for(int s=0;s<8;s++)assertEquals(0,input[start+s],0);continue;}
                    assertEquals((pitches[v]-36)/63f,input[start],0);assertEquals(1,input[start+1],0);
                    assertEquals(v==missing?1:0,input[start+6],0);assertEquals(0,input[start+7],0);
                    for(int p=0;p<4;p++) {
                        float value=input[start+2+p];assertTrue(value>=0 && value<=1);
                        if(v==missing)assertEquals(0,value,0);
                    }
                }
                return flat(input);
            },7,pitches,.55);
            assertEquals(count,calls.get());assertBounds(result,count);
        }
    }

    @Test public void logitsControlEveryParameterBinAndJitterVariesSavedValues() throws Exception {
        Set<Integer> values=new HashSet<>();
        for(int preferred=0;preferred<4;preferred++) {
            final int bin=preferred;
            for(long seed=0;seed<16;seed++) {
                SoundSampler.Result result=SoundSampler.generate(input->{
                    float[] output=new float[16*64];Arrays.fill(output,-80);
                    for(int head=0;head<output.length/4;head++)output[head*4+bin]=80;
                    return output;
                },seed,new int[]{48,60,72,83},0);
                int[][] arrays={result.tones,result.decays,result.drives,result.velocities};
                for(int p=0;p<4;p++)for(int value:arrays[p]) {
                    int low=p==3?16+bin*28:bin*25;
                    int high=p==3?low+27:bin==3?100:low+24;
                    assertTrue(value>=low && value<=high);values.add(value);
                }
            }
        }
        assertTrue("Bin jitter must produce many distinct saved values",values.size()>80);
    }

    @Test public void sameSeedIsDeterministicWithoutChangingSuppliedPitches() throws Exception {
        int[] pitches={48,60,71};int[] original=pitches.clone();
        SoundSampler.Result a=SoundSampler.generate(SoundSamplerTest::flat,113,pitches,.55);
        SoundSampler.Result b=SoundSampler.generate(SoundSamplerTest::flat,113,pitches,.55);
        assertArrayEquals(a.tones,b.tones);assertArrayEquals(a.decays,b.decays);
        assertArrayEquals(a.drives,b.drives);assertArrayEquals(a.velocities,b.velocities);
        // Independent Python replay of Java Random, masked calls, bin draws and jitter.
        assertArrayEquals(new int[]{36,9,22},a.tones);
        assertArrayEquals(new int[]{49,93,42},a.decays);
        assertArrayEquals(new int[]{67,33,74},a.drives);
        assertArrayEquals(new int[]{98,92,63},a.velocities);
        assertArrayEquals(original,pitches);
    }

    @Test public void inferenceFailurePropagatesWithoutRetryOrFallback() throws Exception {
        IOException failure=new IOException("HTP failure");AtomicInteger calls=new AtomicInteger();
        try {
            SoundSampler.generate(input->{calls.incrementAndGet();throw failure;},1,new int[]{48,60},.55);
            fail("Expected inference failure");
        }catch(IOException caught){assertSame(failure,caught);}
        assertEquals(1,calls.get());
    }

    @Test public void invalidOutputAndSettingsAreRejected() throws Exception {
        for(int mode=0;mode<3;mode++) {
            final int selected=mode;
            try {
                SoundSampler.generate(input->{
                    if(selected==0)return null;if(selected==1)return new float[64];
                    float[] output=flat(input);output[1023]=Float.NaN;return output;
                },1,new int[]{48,60},.55);fail("Invalid output accepted");
            }catch(IllegalStateException expected){}
        }
        for(int[] pitches:new int[][]{{48},{60,48},{48,48},{35,60},{48,100}}) {
            try{SoundSampler.generate(SoundSamplerTest::flat,1,pitches,.55);fail("Invalid pitches accepted");}
            catch(IllegalArgumentException expected){}
        }
    }

    private static void assertBounds(SoundSampler.Result result,int count){
        for(int[] values:new int[][]{result.tones,result.decays,result.drives}){
            assertEquals(count,values.length);for(int value:values)assertTrue(value>=0 && value<=100);
        }
        assertEquals(count,result.velocities.length);for(int value:result.velocities)assertTrue(value>=16 && value<=127);
    }
}
