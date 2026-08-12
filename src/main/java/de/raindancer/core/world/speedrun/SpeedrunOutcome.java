package de.raindancer.core.world.speedrun;

import java.time.Duration;
import java.time.Instant;

/**
 * What a finished run was: why it ended, how long it took, and when.
 *
 * @param reason      what ended it — an advancement key prefixed {@code "advancement:"}, a death
 *                    prefixed {@code "death:"} or {@code "death-all"}, or whatever a caller's own
 *                    {@link SpeedrunEndCondition} or manual {@link SpeedrunSession#finish} call passed
 * @param elapsed     the timer's reading at the moment it finished — paused stretches do not count,
 *                    see {@link SpeedrunTimer#elapsed()}
 * @param finishedAt  wall-clock time it finished, for a leaderboard or a log line
 */
public record SpeedrunOutcome(String reason, Duration elapsed, Instant finishedAt) {
}
