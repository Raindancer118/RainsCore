package de.raindancer.core.warp;

/**
 * What came of trying to use a warp.
 *
 * <p>Named answers rather than a boolean, for the same reason everywhere else here: "that warp does
 * not exist", "its world is gone" and "you have to wait" are three different things to tell
 * somebody, and a silent refusal is a command people type twice.
 */
public enum WarpUse {

    /** Off they go. */
    WENT,

    /** No warp by that name. */
    UNKNOWN,

    /** Not theirs to use. */
    NOT_ALLOWED,

    /**
     * The world it is in is not loaded.
     *
     * <p>Worth its own answer: the warp is not broken and should not be deleted — a multiverse
     * server unloads worlds for maintenance, and the warp works again when the world comes back.
     */
    WORLD_MISSING,

    /** Still waiting. */
    ON_COOLDOWN
}
