package de.raindancer.core.world.speedrun;

import java.util.Set;
import java.util.UUID;

/**
 * How {@link SpeedrunLobby} runs the pre-run countdown — a seam so {@link SpeedrunLobby#beginCountdown}
 * is testable without a live server's scheduler, boss bars or sound engine. The production
 * implementation drives a real {@link SpeedrunCountdown}; a test can hand in one that calls
 * {@code onComplete} straight away.
 */
@FunctionalInterface
interface SpeedrunCountdownLauncher {

    /** Starts counting down for exactly {@code participants}; calls {@code onComplete} once, at zero. */
    void begin(Set<UUID> participants, Runnable onComplete);
}
