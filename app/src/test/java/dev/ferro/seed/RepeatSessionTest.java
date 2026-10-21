package dev.ferro.seed;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class RepeatSessionTest {
    private static NoteRecord record(int number) {
        return new NoteRecord("repeat-" + number, number, new int[]{43, 58, 62, 78},
                new int[]{96, 72, 101, 83}, new int[]{0, 35, 100, 61},
                new int[]{100, 0, 80, 29}, new int[]{0, 100, 20, 67},
                false, number, "scratch-v2");
    }

    private static final class Clock implements RepeatSession.Scheduler {
        private final class Task implements RepeatSession.Cancellable, Comparable<Task> {
            final long due = now + delay;
            final long sequence = nextSequence++;
            final Runnable action;
            boolean cancelled;

            Task(Runnable action) { this.action = action; }
            @Override public void cancel() { cancelled = true; }
            @Override public int compareTo(Task other) {
                int time = Long.compare(due, other.due);
                return time != 0 ? time : Long.compare(sequence, other.sequence);
            }
        }

        long now;
        long delay;
        long nextSequence;
        boolean throwOnPost;
        final PriorityQueue<Task> tasks = new PriorityQueue<>();
        final List<Task> posted = new ArrayList<>();

        @Override public RepeatSession.Cancellable post(long delayMs, Runnable action) {
            if (throwOnPost) throw new IllegalStateException("Scheduler unavailable");
            assertTrue(delayMs >= 0);
            delay = delayMs;
            Task task = new Task(action);
            posted.add(task);
            tasks.add(task);
            return task;
        }

        void advanceTo(long time) {
            assertTrue(time >= now);
            while (!tasks.isEmpty() && tasks.peek().due <= time) {
                Task task = tasks.remove();
                now = task.due;
                if (!task.cancelled) task.action.run();
            }
            now = time;
        }
    }

    private static final class Play {
        final long time;
        final int cycle;
        final int number;
        final NoteRecord record;
        final Runnable finished;
        final Consumer<Exception> failed;

        Play(long time, int cycle, int number, NoteRecord record,
             Runnable finished, Consumer<Exception> failed) {
            this.time = time;
            this.cycle = cycle;
            this.number = number;
            this.record = record;
            this.finished = finished;
            this.failed = failed;
        }
    }

    private static final class Harness implements RepeatSession.Listener {
        final Clock clock = new Clock();
        final RepeatSession session = new RepeatSession(clock, this);
        final List<RepeatSession.Generation> generations = new ArrayList<>();
        final List<Long> generationTimes = new ArrayList<>();
        final List<Play> plays = new ArrayList<>();
        final List<Exception> errors = new ArrayList<>();
        boolean autoGenerate;
        Long autoFinishAfter;
        boolean holdLastPlayback;
        boolean throwOnGenerate;
        boolean throwOnPlay;
        boolean finishOnStop;
        int stops;
        int changes;
        boolean consumedBeforeChanged;

        @Override public void generate(RepeatSession.Generation request) {
            generations.add(request);
            generationTimes.add(clock.now);
            if (throwOnGenerate) throw new IllegalStateException("Generation unavailable");
            if (autoGenerate) request.succeeded(record(generations.size()));
        }

        @Override public void play(NoteRecord record, Runnable finished, Consumer<Exception> failed) {
            plays.add(new Play(clock.now, session.cycle(), session.playNumber(), record,
                    finished, failed));
            if (throwOnPlay) throw new IllegalStateException("Audio unavailable");
            if (autoFinishAfter != null && !(holdLastPlayback && plays.size() == 300)) {
                if (autoFinishAfter == 0) finished.run();
                else clock.post(autoFinishAfter, finished);
            }
        }

        @Override public void stopPlayback() {
            stops++;
            if (finishOnStop && !plays.isEmpty()) plays.get(plays.size() - 1).finished.run();
        }

        @Override public void changed() {
            changes++;
            if (session.phase() == RepeatSession.Phase.WAITING_TO_PLAY
                    && session.playNumber() == 0) {
                consumedBeforeChanged = !generations.get(generations.size() - 1).isActive();
            }
        }

        @Override public void failed(Exception error) { errors.add(error); }

        void generateOne() {
            session.start();
            generations.get(generations.size() - 1).succeeded(record(generations.size()));
        }
    }

    @Test public void hundredCyclesHaveExactlyThreePlaysAndRequestedStartTimes() {
        Harness h = new Harness();
        h.autoGenerate = true;
        h.autoFinishAfter = 200L;
        h.session.start();
        h.clock.advanceTo(498_199);
        assertTrue(h.session.isRunning());
        assertEquals(100, h.generations.size());
        assertEquals(300, h.plays.size());
        h.clock.advanceTo(498_200);
        assertFalse(h.session.isRunning());
        assertEquals(RepeatSession.Phase.FINISHED, h.session.phase());
        assertEquals(100, h.session.cycle());
        assertEquals(3, h.session.playNumber());
        assertEquals(0, h.stops);
        assertTrue(h.errors.isEmpty());
        for (int cycle = 1; cycle <= 100; cycle++) {
            long start = (cycle - 1) * 5_000L;
            assertEquals(start, h.generationTimes.get(cycle - 1).longValue());
            for (int play = 1; play <= 3; play++) {
                Play actual = h.plays.get((cycle - 1) * 3 + play - 1);
                assertEquals(start + play * 1_000L, actual.time);
                assertEquals(cycle, actual.cycle);
                assertEquals(play, actual.number);
                assertEquals("repeat-" + cycle, actual.record.id);
            }
        }
        h.clock.advanceTo(1_000_000);
        assertEquals(100, h.generations.size());
        assertEquals(300, h.plays.size());
    }

    @Test public void lastCycleWaitsForItsThirdCompletionWithoutASecondTimer() {
        Harness h = new Harness();
        h.autoGenerate = true;
        h.autoFinishAfter = 200L;
        h.holdLastPlayback = true;
        h.session.start();
        h.clock.advanceTo(1_000_000);
        assertEquals(300, h.plays.size());
        assertEquals(100, h.generations.size());
        assertTrue(h.session.isRunning());
        assertEquals(RepeatSession.Phase.PLAYING, h.session.phase());
        h.plays.get(299).finished.run();
        assertEquals(RepeatSession.Phase.FINISHED, h.session.phase());
        assertFalse(h.session.isRunning());
        assertTrue(h.clock.tasks.isEmpty());
        h.plays.get(299).failed.accept(new Exception("Duplicate late error"));
        assertTrue(h.errors.isEmpty());
    }

    @Test public void slowAudioGatesDueActionsAndEachActualStartSetsTheNextDelay() {
        Harness h = new Harness();
        h.generateOne();
        h.clock.advanceTo(2_500);
        assertEquals(1, h.plays.size());
        assertEquals(1_000, h.plays.get(0).time);
        h.plays.get(0).finished.run();
        assertEquals(2, h.plays.size());
        assertEquals(2_500, h.plays.get(1).time);
        h.clock.advanceTo(2_600);
        h.plays.get(1).finished.run();
        h.clock.advanceTo(3_499);
        assertEquals(2, h.plays.size());
        h.clock.advanceTo(3_500);
        assertEquals(3_500, h.plays.get(2).time);
        h.clock.advanceTo(6_000);
        assertEquals(1, h.generations.size());
        h.plays.get(2).finished.run();
        assertEquals(2, h.generations.size());
        assertEquals(6_000, h.generationTimes.get(1).longValue());
        assertEquals(RepeatSession.Phase.GENERATING, h.session.phase());
    }

    @Test public void generationLatencyDoesNotConsumeTheFirstPlaybackDelay() {
        Harness h = new Harness();
        h.session.start();
        h.clock.advanceTo(8_000);
        assertTrue(h.plays.isEmpty());
        h.generations.get(0).succeeded(record(1));
        assertTrue(h.consumedBeforeChanged);
        h.clock.advanceTo(8_999);
        assertTrue(h.plays.isEmpty());
        h.clock.advanceTo(9_000);
        assertEquals(9_000, h.plays.get(0).time);
    }

    @Test public void startWhileRunningAndStopWhileIdleAreNoOps() {
        Harness h = new Harness();
        assertEquals(RepeatSession.Phase.IDLE, h.session.phase());
        assertEquals(0, h.changes);
        h.session.stop();
        assertEquals(0, h.stops);
        assertEquals(0, h.changes);
        h.session.start();
        long token = h.session.runToken();
        int changes = h.changes;
        h.session.start();
        assertEquals(token, h.session.runToken());
        assertEquals(changes, h.changes);
        assertEquals(1, h.generations.size());
        h.session.stop();
        h.session.stop();
        assertEquals(1, h.stops);
    }

    @Test public void cancelDuringGenerationInvalidatesRequestsAndAllowsFreshStart() {
        Harness h = new Harness();
        h.session.start();
        RepeatSession.Generation old = h.generations.get(0);
        long token = h.session.runToken();
        assertTrue(old.isActive());
        h.session.stop();
        assertFalse(old.isActive());
        assertNotEquals(token, h.session.runToken());
        h.session.start();
        RepeatSession.Generation fresh = h.generations.get(1);
        assertTrue(fresh.requestId() > old.requestId());
        old.succeeded(record(1));
        old.failed(new Exception("Stale inference"));
        assertTrue(fresh.isActive());
        assertTrue(h.clock.tasks.isEmpty());
        fresh.succeeded(record(2));
        assertFalse(fresh.isActive());
        h.clock.advanceTo(1_000);
        assertEquals("repeat-2", h.plays.get(0).record.id);
        assertTrue(h.errors.isEmpty());
    }

    @Test public void cancelDuringDelayMakesEvenDeliveredCancelledTimerHarmless() {
        Harness h = new Harness();
        h.generateOne();
        Runnable oldTimer = h.clock.posted.get(0).action;
        h.session.stop();
        assertTrue(h.clock.posted.get(0).cancelled);
        oldTimer.run();
        h.clock.advanceTo(100_000);
        assertTrue(h.plays.isEmpty());
        assertEquals(1, h.generations.size());
        assertEquals(RepeatSession.Phase.CANCELLED, h.session.phase());
    }

    @Test public void cancelDuringAudioGuardsSynchronousStopCompletionAndLaterFailure() {
        Harness h = new Harness();
        h.finishOnStop = true;
        h.generateOne();
        h.clock.advanceTo(1_000);
        Play old = h.plays.get(0);
        h.session.stop();
        old.failed.accept(new Exception("Stopped audio"));
        old.finished.run();
        h.clock.advanceTo(100_000);
        assertEquals(1, h.stops);
        assertEquals(1, h.plays.size());
        assertEquals(1, h.generations.size());
        assertTrue(h.errors.isEmpty());
        assertEquals(RepeatSession.Phase.CANCELLED, h.session.phase());
    }

    @Test public void oldAudioAndTimerCannotCompleteOrAdvanceRestartedPlayback() {
        Harness h = new Harness();
        h.generateOne();
        h.clock.advanceTo(1_000);
        Play old = h.plays.get(0);
        Runnable oldTimer = h.clock.posted.get(1).action;
        h.session.stop();
        h.generateOne();
        h.clock.advanceTo(2_000);
        Play fresh = h.plays.get(1);
        oldTimer.run();
        old.finished.run();
        old.failed.accept(new Exception("Old run"));
        h.clock.advanceTo(4_000);
        assertEquals(2, h.plays.size());
        assertEquals(RepeatSession.Phase.PLAYING, h.session.phase());
        fresh.finished.run();
        assertEquals(3, h.plays.size());
        assertEquals(4_000, h.plays.get(2).time);
        assertEquals("repeat-2", h.plays.get(2).record.id);
        assertTrue(h.errors.isEmpty());
    }

    @Test public void duplicateGenerationAndAudioCallbacksCannotRescheduleOrFail() {
        Harness h = new Harness();
        h.generateOne();
        h.clock.advanceTo(500);
        h.generations.get(0).succeeded(record(99));
        h.generations.get(0).failed(new Exception("Duplicate generation"));
        h.clock.advanceTo(1_000);
        Play first = h.plays.get(0);
        assertEquals("repeat-1", first.record.id);
        first.finished.run();
        first.finished.run();
        first.failed.accept(new Exception("Already finished"));
        h.clock.posted.get(0).action.run();
        h.clock.advanceTo(1_999);
        assertEquals(1, h.plays.size());
        h.clock.advanceTo(2_000);
        assertEquals(2, h.plays.size());
        assertTrue(h.errors.isEmpty());
    }

    @Test public void generationFailureStopsOnceAndRejectsLateSuccess() {
        Harness h = new Harness();
        h.session.start();
        RepeatSession.Generation request = h.generations.get(0);
        Exception error = new Exception("Save failed");
        request.failed(error);
        request.failed(error);
        request.succeeded(record(1));
        assertFalse(request.isActive());
        assertFalse(h.session.isRunning());
        assertEquals(RepeatSession.Phase.FAILED, h.session.phase());
        assertEquals(1, h.stops);
        assertEquals(1, h.errors.size());
        assertSame(error, h.errors.get(0));
        assertTrue(h.clock.tasks.isEmpty());
        h.session.start();
        assertTrue(h.generations.get(1).isActive());
    }

    @Test public void audioFailureFollowedByFinishCannotResumeSession() {
        Harness h = new Harness();
        h.generateOne();
        h.clock.advanceTo(1_000);
        Play first = h.plays.get(0);
        Exception error = new Exception("Audio write failed");
        first.failed.accept(error);
        first.finished.run();
        first.failed.accept(error);
        h.clock.posted.get(1).action.run();
        h.clock.advanceTo(100_000);
        assertEquals(RepeatSession.Phase.FAILED, h.session.phase());
        assertEquals(1, h.errors.size());
        assertSame(error, h.errors.get(0));
        assertEquals(1, h.plays.size());
        assertEquals(1, h.generations.size());
    }

    @Test public void thrownGenerationPlaybackAndSchedulerErrorsTerminateCleanly() {
        Harness generation = new Harness();
        generation.throwOnGenerate = true;
        generation.session.start();
        assertEquals(RepeatSession.Phase.FAILED, generation.session.phase());
        assertEquals(1, generation.errors.size());

        Harness audio = new Harness();
        audio.throwOnPlay = true;
        audio.generateOne();
        audio.clock.advanceTo(100_000);
        assertEquals(RepeatSession.Phase.FAILED, audio.session.phase());
        assertEquals(1, audio.errors.size());
        assertEquals(1, audio.plays.size());

        Harness scheduler = new Harness();
        scheduler.clock.throwOnPost = true;
        scheduler.generateOne();
        assertEquals(RepeatSession.Phase.FAILED, scheduler.session.phase());
        assertEquals(1, scheduler.errors.size());
        assertTrue(scheduler.plays.isEmpty());
    }

    @Test public void everyPlaybackUsesTheSameUnmodifiedPersistedNotesAndControls() {
        Harness h = new Harness();
        h.session.start();
        NoteRecord original = record(1);
        h.generations.get(0).succeeded(original);
        original.pitches[0] = 42;
        original.velocities[0] = 1;
        original.tones[0] = 99;
        original.decays[0] = 0;
        original.drives[0] = 99;
        h.clock.advanceTo(1_000);
        Play first = h.plays.get(0);
        assertExactRecord(first.record);
        first.record.pitches[0] = 41;
        first.record.velocities[0] = 2;
        first.record.tones[0] = 98;
        first.record.decays[0] = 1;
        first.record.drives[0] = 98;
        first.finished.run();
        h.clock.advanceTo(2_000);
        assertExactRecord(h.plays.get(1).record);
        h.plays.get(1).finished.run();
        h.clock.advanceTo(3_000);
        assertExactRecord(h.plays.get(2).record);
    }

    @Test public void synchronousAudioCompletionsStillKeepStartIntervals() {
        Harness h = new Harness();
        h.autoFinishAfter = 0L;
        h.generateOne();
        h.clock.advanceTo(4_999);
        assertEquals(3, h.plays.size());
        assertEquals(RepeatSession.Phase.WAITING_FOR_NEXT_CYCLE, h.session.phase());
        assertEquals(1_000, h.plays.get(0).time);
        assertEquals(2_000, h.plays.get(1).time);
        assertEquals(3_000, h.plays.get(2).time);
        h.clock.advanceTo(5_000);
        assertEquals(2, h.generations.size());
    }

    @Test public void invalidSavedResultFailsWithoutSchedulingAnyPlayback() {
        Harness h = new Harness();
        h.session.start();
        h.generations.get(0).succeeded(null);
        assertEquals(RepeatSession.Phase.FAILED, h.session.phase());
        assertEquals(1, h.errors.size());
        assertTrue(h.clock.tasks.isEmpty());
        assertTrue(h.plays.isEmpty());
    }

    private static void assertExactRecord(NoteRecord actual) {
        NoteRecord expected = record(1);
        assertEquals(expected.id, actual.id);
        assertEquals(expected.createdAt, actual.createdAt);
        assertEquals(expected.modelVersion, actual.modelVersion);
        assertEquals(expected.seed, actual.seed);
        assertArrayEquals(expected.pitches, actual.pitches);
        assertArrayEquals(expected.velocities, actual.velocities);
        assertArrayEquals(expected.tones, actual.tones);
        assertArrayEquals(expected.decays, actual.decays);
        assertArrayEquals(expected.drives, actual.drives);
    }
}
