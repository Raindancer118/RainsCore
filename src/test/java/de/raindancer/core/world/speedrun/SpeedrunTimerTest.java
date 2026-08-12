package de.raindancer.core.world.speedrun;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one thing a naive stopwatch gets wrong: accumulating correctly across more than one
 * pause/resume cycle rather than only remembering the most recent stretch.
 */
class SpeedrunTimerTest {

    @Test
    void neverStartedIsZero() {
        SpeedrunTimer timer = new SpeedrunTimer();

        assertThat(timer.elapsed()).isZero();
        assertThat(timer.isRunning()).isFalse();
    }

    @Test
    void elapsedGrowsWhileRunning() throws InterruptedException {
        SpeedrunTimer timer = new SpeedrunTimer();
        timer.start();
        Thread.sleep(20);

        assertThat(timer.elapsed()).isGreaterThan(Duration.ZERO);
        assertThat(timer.isRunning()).isTrue();
    }

    @Test
    void pausedTimeDoesNotCount() throws InterruptedException {
        SpeedrunTimer timer = new SpeedrunTimer();
        timer.start();
        Thread.sleep(20);
        timer.pause();
        Duration atPause = timer.elapsed();
        assertThat(timer.isRunning()).isFalse();

        Thread.sleep(50);   // time passes while paused

        // Reading it again while paused must not move — a naive implementation that keeps measuring
        // from the original start time would report the 50ms of pause too.
        assertThat(timer.elapsed()).isEqualTo(atPause);
    }

    @Test
    void accumulatesAcrossMultiplePauseResumeCycles() throws InterruptedException {
        SpeedrunTimer timer = new SpeedrunTimer();
        timer.start();
        Thread.sleep(20);
        timer.pause();
        Duration afterFirstStretch = timer.elapsed();

        Thread.sleep(30);   // paused; must not count
        timer.resume();
        Thread.sleep(20);
        timer.pause();
        Duration afterSecondStretch = timer.elapsed();

        Thread.sleep(30);   // paused again; must not count
        timer.resume();
        Thread.sleep(20);
        Duration whileRunningAgain = timer.elapsed();

        assertThat(afterSecondStretch).isGreaterThan(afterFirstStretch);
        // The second running stretch keeps adding on top of both earlier ones, not replacing them.
        assertThat(whileRunningAgain).isGreaterThan(afterSecondStretch);
    }

    @Test
    void resumeWithoutPauseIsANoOp() throws InterruptedException {
        SpeedrunTimer timer = new SpeedrunTimer();
        timer.start();
        Thread.sleep(10);
        timer.resume();   // already running
        Thread.sleep(10);

        assertThat(timer.isRunning()).isTrue();
        assertThat(timer.elapsed()).isGreaterThan(Duration.ofMillis(15));
    }

    @Test
    void pauseWithoutStartIsANoOp() {
        SpeedrunTimer timer = new SpeedrunTimer();
        timer.pause();

        assertThat(timer.elapsed()).isZero();
        assertThat(timer.isRunning()).isFalse();
    }

    @Test
    void stopFreezesTheReading() throws InterruptedException {
        SpeedrunTimer timer = new SpeedrunTimer();
        timer.start();
        Thread.sleep(20);
        timer.stop();
        Duration atStop = timer.elapsed();

        Thread.sleep(30);

        assertThat(timer.elapsed()).isEqualTo(atStop);
        assertThat(timer.isRunning()).isFalse();
    }
}
