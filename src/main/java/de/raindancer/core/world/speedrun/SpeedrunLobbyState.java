package de.raindancer.core.world.speedrun;

/**
 * Where the speedrun lobby's one world is, independent of whether a {@link SpeedrunSession} exists
 * at all — {@link SpeedrunState} is the session's own clock state and only makes sense once a
 * session has been built; this is what a joining player and the GUI actually need to know.
 */
public enum SpeedrunLobbyState {
    /** No run under way. The compass opens the config screen; the green block starts one. */
    READY,
    /** The green block was pressed; every participant is frozen and a boss bar is ticking down. */
    COUNTDOWN,
    /** Timing, same as {@link SpeedrunState#RUNNING}. */
    RUNNING,
    /** Every participant is offline; the clock is stopped. */
    PAUSED,
    /** A run ended and is waiting for every participant to leave before the world resets. */
    FINISHED
}
