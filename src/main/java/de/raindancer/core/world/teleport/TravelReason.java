package de.raindancer.core.world.teleport;

/**
 * Why a journey was called off, or never started.
 *
 * <h2>Why an enum and not a sentence</h2>
 * Because the sentence belongs to the plugin. A library that handed back {@code "you moved"} would
 * be a library deciding what the server sounds like, in one language, past every {@code messages.yml}
 * on it. Named answers instead: the plugin words each one, and a new one added here is a compiler
 * error at every plugin rather than a line of English nobody notices is untranslated.
 *
 * <p>Each of these is also a genuinely different thing to say. "You moved" and "there is nowhere
 * safe to put you" are not the same news, and neither of them is "that world is not loaded right
 * now" — which is not even a fault.
 */
public enum TravelReason {

    /** They walked off the block they were standing on. */
    MOVED,

    /** Something hurt them mid-wait. */
    HURT,

    /** They are already part-way through going somewhere else. */
    ALREADY_TRAVELLING,

    /**
     * The destination's world is not loaded.
     *
     * <p>Not a fault and not a reason to delete anything: a multiverse server unloads worlds for
     * maintenance and the place works again when the world comes back.
     */
    WORLD_MISSING,

    /**
     * Nowhere within the search radius was safe.
     *
     * <p>A refusal rather than a fallback, on purpose. Falling back to the exact spot puts the
     * player in the place already known to be dangerous, which is the whole thing the safety
     * package exists to stop.
     */
    NOWHERE_SAFE,

    /** The ground could not be read at all — a world unloaded mid-check, most likely. */
    COULD_NOT_CHECK,

    /** Something else refused the teleport: a world border, a closed dimension, another plugin. */
    TELEPORT_REFUSED,

    /** There is nothing to count the warm-up with, which means the plugin is going down. */
    CANNOT_SCHEDULE
}
