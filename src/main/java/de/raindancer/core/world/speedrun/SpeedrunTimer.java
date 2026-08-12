package de.raindancer.core.world.speedrun;

import java.time.Duration;

/**
 * A stopwatch. Nothing else — no lifecycle beyond running or not, no notion of why it started or
 * stopped. Full run lifecycle ({@code NOT_STARTED}/{@code PAUSED}/{@code FINISHED}) belongs on
 * {@link SpeedrunSession}; this only measures time.
 *
 * <h2>Why {@code System.nanoTime()}, not {@code Instant}</h2>
 * The clock is read from a display tick (once a second, to show elapsed time on a boss bar or
 * scoreboard) and mutated from event handlers — an advancement, a death, somebody logging out —
 * which on Paper can be a different thread from the tick. {@code nanoTime()} is monotonic and immune
 * to the wall clock being adjusted mid-run; {@code Instant.now()} is not, and a run that spans a
 * server's NTP correction would otherwise report a wrong duration.
 *
 * <h2>Thread safety</h2>
 * {@code synchronized} methods, matching the rest of this library's mutable-but-rarely-contended
 * state (see {@code Achievements}). A speedrun timer is touched a handful of times a second at most,
 * so there is nothing here that a lock costs anything to protect.
 */
public final class SpeedrunTimer {

    /** {@code nanoTime()} when the current running stretch began; meaningless while not running. */
    private long startedAt;
    /** The total of every stretch that has already ended, accumulated across pause/resume. */
    private long accumulatedNanos;
    private boolean running;

    /** Starts timing from zero. Calling it again while running restarts the running stretch, not the total. */
    public synchronized void start() {
        accumulatedNanos = 0L;
        startedAt = System.nanoTime();
        running = true;
    }

    /** Stops timing without discarding what has accumulated. A no-op if not running. */
    public synchronized void pause() {
        if (!running) {
            return;
        }
        accumulatedNanos += System.nanoTime() - startedAt;
        running = false;
    }

    /** Resumes after a {@link #pause()}. A no-op if already running. */
    public synchronized void resume() {
        if (running) {
            return;
        }
        startedAt = System.nanoTime();
        running = true;
    }

    /** Stops timing for good. Unlike {@link #pause()}, this is the last thing that happens to the timer. */
    public synchronized void stop() {
        pause();
    }

    /**
     * How long the timer has run in total: every finished stretch, plus the one in progress if it is
     * currently running. Correct to call at any point in the run's life, including before {@link #start()}.
     */
    public synchronized Duration elapsed() {
        long total = accumulatedNanos;
        if (running) {
            total += System.nanoTime() - startedAt;
        }
        return Duration.ofNanos(total);
    }

    /** Whether the timer is currently accumulating time. */
    public synchronized boolean isRunning() {
        return running;
    }
}
