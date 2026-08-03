package de.raindancer.core.vanish;

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
}
