package dev.ferro.seed;

import java.util.Random;

/** Samples saved per-note settings around the separate HTP sound model. */
public final class SoundSampler {
    public static final int BATCH=16, INPUT_SIZE=32, OUTPUT_SIZE=64;
    private static final int[][] LOW={{0,25,50,75},{0,25,50,75},{0,25,50,75},{16,44,72,100}};
    private static final int[][] HIGH={{24,49,74,100},{24,49,74,100},{24,49,74,100},{43,71,99,127}};

    private SoundSampler() { }

    public static final class Result {
        public final int[] velocities, tones, decays, drives;
        private Result(int[][] parameters) {
            tones=new int[parameters.length];decays=new int[parameters.length];
            drives=new int[parameters.length];velocities=new int[parameters.length];
            for(int v=0;v<parameters.length;v++) {
                tones[v]=parameters[v][0];decays[v]=parameters[v][1];
                drives[v]=parameters[v][2];velocities[v]=parameters[v][3];
            }
        }
    }

    public static Result generate(ChordSampler.Inference inference,long seed,int[] sortedPitches,
                                  double exploration) throws Exception {
        if(inference==null || sortedPitches==null || sortedPitches.length<2 || sortedPitches.length>4
                || !Double.isFinite(exploration) || exploration<0 || exploration>1)
            throw new IllegalArgumentException("음색 생성 설정이 올바르지 않습니다.");
        int[] pitches=sortedPitches.clone();
        for(int v=0;v<pitches.length;v++) if(pitches[v]<36 || pitches[v]>99 || (v>0 && pitches[v]<=pitches[v-1]))
            throw new IllegalArgumentException("음높이는 중복 없이 오름차순이어야 합니다.");
        Random random=new Random(seed);
        int[][][] candidates=new int[BATCH][pitches.length][4];
        for(int[][] chord:candidates) for(int[] parameters:chord) for(int p=0;p<4;p++)
            parameters[p]=p==3?16+random.nextInt(112):random.nextInt(101);
        double temperature=.75+.9*exploration;
        for(int missing=0;missing<pitches.length;missing++) {
            float[] context=new float[BATCH*INPUT_SIZE];
            for(int b=0;b<BATCH;b++) for(int v=0;v<pitches.length;v++) {
                int start=b*INPUT_SIZE+v*8;
                context[start]=(pitches[v]-36)/63f;context[start+1]=1;
                if(v==missing)context[start+6]=1;
                else for(int p=0;p<4;p++)context[start+2+p]=candidates[b][v][p]/(p==3?127f:100f);
            }
            float[] logits=inference.infer(context);
            if(logits==null || logits.length!=BATCH*OUTPUT_SIZE)
                throw new IllegalStateException("음색 NPU 출력 크기가 올바르지 않습니다.");
            for(float logit:logits)if(!Float.isFinite(logit))
                throw new IllegalStateException("음색 NPU 출력에 잘못된 값이 있습니다.");
            for(int b=0;b<BATCH;b++) for(int parameter=0;parameter<4;parameter++) {
                int start=b*OUTPUT_SIZE+missing*16+parameter*4;
                double maximum=-Double.MAX_VALUE;
                for(int bin=0;bin<4;bin++)maximum=Math.max(maximum,logits[start+bin]/temperature);
                double total=0;double[] weights=new double[4];
                for(int bin=0;bin<4;bin++) {
                    weights[bin]=Math.exp(logits[start+bin]/temperature-maximum)+.02*exploration;
                    total+=weights[bin];
                }
                double draw=random.nextDouble()*total;int selected=3;
                for(int bin=0;bin<4;bin++){draw-=weights[bin];if(draw<=0){selected=bin;break;}}
                int low=LOW[parameter][selected],high=HIGH[parameter][selected];
                candidates[b][missing][parameter]=low+random.nextInt(high-low+1);
            }
        }
        return new Result(candidates[random.nextInt(BATCH)]);
    }
}
