package dev.ferro.seed;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs 100 generate/save/play cycles without blocking a thread.
 *
 * <p>Except for {@link Generation#isActive()}, all methods and callbacks must run on the
 * scheduler's thread (the Android main thread). Generation succeeds only after the exact result
 * has been persisted. A worker may check isActive before committing its result; the integration
 * must serialize that commit with cancellation if it needs an atomic cancellation boundary.</p>
 */
public final class RepeatSession {
    public static final int TOTAL_CYCLES = 100;
    public static final int PLAYS_PER_CYCLE = 3;
    public static final long PLAY_INTERVAL_MS = 1_000L;
    public static final long NEXT_CYCLE_DELAY_MS = 2_000L;

    public enum Phase {
        IDLE, GENERATING, WAITING_TO_PLAY, PLAYING, WAITING_FOR_NEXT_CYCLE,
        FINISHED, CANCELLED, FAILED
    }

    public interface Cancellable { void cancel(); }

    public interface Scheduler {
        Cancellable post(long delayMs, Runnable action);
    }

    public interface Listener {
        void generate(Generation request);
        void play(NoteRecord record, Runnable finished, Consumer<Exception> failed);
        void stopPlayback();
        void changed();
        void failed(Exception error);
    }

    /** A one-shot generation request; stale or duplicate results have no effect. */
    public final class Generation {
        private final long token;
        private final long id;

        private Generation(long token, long id) {
            this.token = token;
            this.id = id;
        }

        public long requestId() { return id; }

        /** Safe to query from a worker; all fields read here are final or volatile. */
        public boolean isActive() {
            return running && epoch == token && pendingGeneration == this;
        }

        public void succeeded(NoteRecord persistedRecord) {
            if (!isActive()) return;
            // A rendered UI or another callback must already see this request as consumed.
            pendingGeneration = null;
            try {
                currentRecord = Objects.requireNonNull(persistedRecord, "Missing saved result").copy();
            } catch (RuntimeException error) {
                fail(error);
                return;
            }
            phase = Phase.WAITING_TO_PLAY;
            if (schedule(Action.PLAY, PLAY_INTERVAL_MS)) listener.changed();
        }

        public void failed(Exception error) {
            if (isActive()) fail(error);
        }
    }

    private enum Action { PLAY, GENERATE }

    private final class Wait {
        final long token = epoch;
        final Action action;
        Cancellable handle;
        boolean due;

        Wait(Action action) { this.action = action; }
    }

    private final class Playback {
        final long token = epoch;

        boolean isActive() {
            return running && epoch == token && activePlayback == this;
        }

        void finished() {
            if (!isActive()) return;
            activePlayback = null;
            if (cycle == TOTAL_CYCLES && playNumber == PLAYS_PER_CYCLE) {
                invalidate(Phase.FINISHED);
                listener.changed();
            } else if (waiting != null && waiting.due) {
                dispatchDue();
            } else {
                phase = playNumber < PLAYS_PER_CYCLE
                        ? Phase.WAITING_TO_PLAY : Phase.WAITING_FOR_NEXT_CYCLE;
                listener.changed();
            }
        }

        void failed(Exception error) {
            if (isActive()) fail(error);
        }
    }

    private final Scheduler scheduler;
    private final Listener listener;
    private volatile boolean running;
    private volatile long epoch;
    private volatile Generation pendingGeneration;
    private long nextRequestId;
    private int cycle;
    private int playNumber;
    private Phase phase = Phase.IDLE;
    private NoteRecord currentRecord;
    private Playback activePlayback;
    private Wait waiting;

    public RepeatSession(Scheduler scheduler, Listener listener) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public boolean isRunning() { return running; }
    public long runToken() { return epoch; }
    public int cycle() { return cycle; }
    public int playNumber() { return playNumber; }
    public Phase phase() { return phase; }

    public void start() {
        if (running) return;
        epoch++;
        running = true;
        cycle = 1;
        playNumber = 0;
        currentRecord = null;
        generate();
    }

    public void stop() {
        if (!running) return;
        invalidate(Phase.CANCELLED);
        try {
            listener.stopPlayback();
        } finally {
            listener.changed();
        }
    }

    private void generate() {
        phase = Phase.GENERATING;
        Generation request = new Generation(epoch, ++nextRequestId);
        pendingGeneration = request;
        listener.changed();
        if (!request.isActive()) return;
        try {
            listener.generate(request);
        } catch (RuntimeException error) {
            request.failed(error);
        }
    }

    private boolean schedule(Action action, long delayMs) {
        Wait next = new Wait(action);
        waiting = next;
        try {
            next.handle = Objects.requireNonNull(scheduler.post(delayMs, () -> {
                if (!running || epoch != next.token || waiting != next || next.due) return;
                next.due = true;
                dispatchDue();
            }), "Missing cancellation handle");
            return running && epoch == next.token;
        } catch (RuntimeException error) {
            fail(error);
            return false;
        }
    }

    private void dispatchDue() {
        Wait next = waiting;
        if (!running || next == null || !next.due || activePlayback != null) return;
        waiting = null;
        if (next.action == Action.GENERATE) {
            cycle++;
            playNumber = 0;
            currentRecord = null;
            generate();
        } else {
            play();
        }
    }

    private void play() {
        Playback playback = new Playback();
        activePlayback = playback;
        playNumber++;
        phase = Phase.PLAYING;
        // Measure each delay from this start, independently of the audio completion callback.
        // A due action waits for completion, so slow playback can never overlap another play.
        if (playNumber < PLAYS_PER_CYCLE) {
            if (!schedule(Action.PLAY, PLAY_INTERVAL_MS)) return;
        } else if (cycle < TOTAL_CYCLES) {
            if (!schedule(Action.GENERATE, NEXT_CYCLE_DELAY_MS)) return;
        }
        listener.changed();
        if (!playback.isActive()) return;
        try {
            listener.play(currentRecord.copy(), playback::finished, playback::failed);
        } catch (RuntimeException error) {
            playback.failed(error);
        }
    }

    private void fail(Exception error) {
        if (!running) return;
        Exception reason = error != null ? error : new IllegalStateException("Repeat session failed");
        invalidate(Phase.FAILED);
        try {
            listener.stopPlayback();
        } catch (RuntimeException stopError) {
            if (reason != stopError) reason.addSuppressed(stopError);
        }
        listener.changed();
        listener.failed(reason);
    }

    private void invalidate(Phase terminalPhase) {
        // Invalidate identities before stopPlayback can synchronously report an old completion.
        running = false;
        epoch++;
        pendingGeneration = null;
        activePlayback = null;
        currentRecord = null;
        Wait previous = waiting;
        waiting = null;
        phase = terminalPhase;
        if (previous != null && previous.handle != null) previous.handle.cancel();
    }
}
