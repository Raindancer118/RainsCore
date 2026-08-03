package de.raindancer.core.world.combat;

/**
 * Whether an attack may go ahead, and why not.
 *
 * <h2>Why not a boolean</h2>
 * Because the player has to be told something, and "you cannot do that" is the message that makes
 * people leave a server. There is a real difference between "PvP is off here", "this world is
 * peaceful" and "that player is protected", and only the first of those is something they can work
 * around by walking somewhere else.
 *
 * <p>Each one carries the key of what to say rather than the sentence, so the wording lives in
 * {@code messages.yml} with everything else.
 */
public enum Verdict {

    /** Go ahead. */
    ALLOWED(null),

    /** One player may not hurt another here. */
    NO_PVP("combat.no-pvp"),

    /** A player and a mob may not hurt each other here. */
    NO_PVE("combat.no-pve"),

    /**
     * Something else said no — a claim, an arena, a plugin's own rule.
     *
     * <p>Deliberately vague, because this library does not know why. Whatever answered it says so
     * itself; a message from here would be a guess.
     */
    PROTECTED("combat.protected");

    private final String reasonKey;

    Verdict(String reasonKey) {
        this.reasonKey = reasonKey;
    }

    public boolean allowed() {
        return this == ALLOWED;
    }

    /** Which line of the message file explains this, or null when there is nothing to explain. */
    public String reasonKey() {
        return reasonKey;
    }
}
