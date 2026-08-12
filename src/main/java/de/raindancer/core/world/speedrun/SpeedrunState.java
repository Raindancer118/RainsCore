package de.raindancer.core.world.speedrun;

/**
 * Where a {@link SpeedrunSession} is in its life. One direction, except for {@code PAUSED}, which is
 * the only state a run can leave for somewhere other than {@code FINISHED}.
 */
public enum SpeedrunState {
    /** Built, but {@link SpeedrunSession#start()} has not been called yet. */
    NOT_STARTED,
    /** Timing, and listening for whatever ends it. */
    RUNNING,
    /** Timing stopped — the roster went empty — but not over; {@link SpeedrunSession#resume()} returns it to {@code RUNNING}. */
    PAUSED,
    /** Over. {@link SpeedrunSession#finish} is a no-op from here on, and every end condition has been disarmed. */
    FINISHED
}
