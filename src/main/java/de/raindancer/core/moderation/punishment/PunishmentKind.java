package de.raindancer.core.moderation.punishment;

/**
 * What was done to somebody.
 *
 * <p>Each says whether it is a <em>state</em> a player is in or a <em>thing that happened</em> to
 * them, because that decides everything else: a state can be active, expire and be lifted, while a
 * kick is over the moment it lands and only ever appears in the history.
 */
public enum PunishmentKind {

    /** Cannot join at all. */
    BAN(true, "banned"),

    /** Can join, cannot speak. */
    MUTE(true, "muted"),

    /** Thrown off, once. Over as soon as it happens. */
    KICK(false, "kicked"),

    /** A note on their record. Never stops them doing anything. */
    WARNING(false, "warned"),

    /**
     * Cannot build or break anything.
     *
     * <p>The one a plugin other than a moderation plugin usually wants: a claims module can jail
     * somebody's hands without deciding they should be off the server.
     */
    FREEZE(true, "frozen");

    private final boolean lasting;
    private final String past;

    PunishmentKind(boolean lasting, String past) {
        this.lasting = lasting;
        this.past = past;
    }

    /** Whether this is a state somebody is in, rather than something that happened once. */
    public boolean isLasting() {
        return lasting;
    }

    /** "banned", "muted" — for a sentence like "You are banned." */
    public String past() {
        return past;
    }
}
