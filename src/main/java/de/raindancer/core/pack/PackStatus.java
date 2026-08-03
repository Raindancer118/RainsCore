package de.raindancer.core.pack;

/**
 * Where one player is with the pack.
 *
 * <p>Worth keeping, because "did that work" is otherwise unanswerable: a plugin drawing a menu with
 * custom glyphs needs to know whether this particular player can actually see them, and a server
 * owner debugging "the icons are broken" needs to know whether the pack was refused, failed to
 * download, or arrived and was simply wrong.
 */
public enum PackStatus {

    /** Never offered it — a player who just joined, or one it was taken off. */
    NOT_SENT,

    /** Offered, and the client has not said anything yet. */
    SENT,

    /** The client took it and is wearing it. This is the only state where the assets are live. */
    LOADED,

    /** The player said no. Their choice; nothing here overrides it. */
    DECLINED,

    /** The client tried and could not — a bad URL, an unreachable server, a wrong hash. */
    FAILED,

    /** The client already had it cached and did not need to download it. */
    ALREADY_HAD_IT;

    /** Whether the assets are actually on this player's screen. */
    public boolean isWearing() {
        return this == LOADED || this == ALREADY_HAD_IT;
    }

    /** Whether it is worth offering again — a failure is, a refusal is not. */
    public boolean isWorthRetrying() {
        return this == FAILED;
    }
}
