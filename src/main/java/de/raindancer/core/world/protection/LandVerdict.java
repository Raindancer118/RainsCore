package de.raindancer.core.world.protection;

/**
 * The three possible answers, and the third one is the point.
 *
 * <p>A boolean cannot say "nobody is here to answer that", and the difference matters enormously. Asked
 * whether a world is safe to regenerate, {@code ALLOWED} means "checked, there is nothing claimed in it" and
 * {@code UNKNOWN} means "no claims plugin is installed, so I have no idea" — and those two must not lead to
 * the same behaviour when the next step deletes three worlds.
 *
 * <p>So the caller decides which way to fail, and {@link #orRefuse()} and {@link #orAllow()} make that
 * decision visible at the call site rather than hidden in a default.
 */
public enum LandVerdict {

    /** Somebody answered, and the answer is yes. */
    ALLOWED,

    /** Somebody answered, and the answer is no. */
    REFUSED,

    /**
     * Nobody answered — no provider is registered, or it could not tell.
     *
     * <p>Not a synonym for either of the others. Treating it as yes is how an uninstalled module turns into
     * deleted builds; treating it as no is how a server with no claims plugin finds half its features
     * refusing to work. Which is right depends entirely on what the caller is about to do.
     */
    UNKNOWN;

    public boolean isAllowed() {
        return this == ALLOWED;
    }

    public boolean isRefused() {
        return this == REFUSED;
    }

    public boolean isUnknown() {
        return this == UNKNOWN;
    }

    /**
     * Yes only when somebody actually said yes.
     *
     * <p>For the irreversible things: deleting a world, pasting over a region, clearing an area. Not knowing
     * has to stop them.
     */
    public boolean orRefuse() {
        return this == ALLOWED;
    }

    /**
     * Yes unless somebody actually said no.
     *
     * <p>For the ordinary things a player does, where a server that never installed a claims plugin should
     * behave as though claims did not exist.
     */
    public boolean orAllow() {
        return this != REFUSED;
    }
}
