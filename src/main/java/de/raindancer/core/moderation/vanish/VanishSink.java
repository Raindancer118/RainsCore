package de.raindancer.core.moderation.vanish;

import java.util.UUID;

/**
 * The one thing in this package that talks to the server.
 *
 * <p>Who is hidden, who may see them, and what should happen when somebody vanishes is bookkeeping
 * and is tested without a server. Hiding an entity from every player is not.
 */
public interface VanishSink {

    /**
     * Hides this player from everybody except the people named.
     *
     * <p>The exceptions are passed in rather than asked for, so this stays a sink with no opinions
     * and no reference back to {@link Vanish} — which would otherwise have to be constructed before
     * itself.
     *
     * @param mayStillSee who is allowed to keep seeing them
     */
    void hide(UUID who, java.util.Set<UUID> mayStillSee);

    /** Shows them again. */
    void show(UUID who);

    /** Turns flight on or off. */
    void allowFlight(UUID who, boolean allowed);

    /** Whether other players bump into them. */
    void collidable(UUID who, boolean collides);

    /** Whether their joining and leaving is announced. */
    void silentJoinLeave(UUID who, boolean silent);

    /**
     * Says to the server what it would have said had they actually logged off.
     *
     * <p>The wording is the game's own, not a plugin's — the whole value of the line is that it is
     * indistinguishable from a real departure. A vanished moderator whose disappearance is announced as
     * "X vanished" is a moderator everybody knows is watching.
     *
     * @param exceptThem who should not be told, because they know
     */
    void announceDeparture(UUID who, java.util.Set<UUID> exceptThem);

    /** The counterpart: what the server would have said had they just joined. */
    void announceArrival(UUID who, java.util.Set<UUID> exceptThem);
}
