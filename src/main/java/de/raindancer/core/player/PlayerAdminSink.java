package de.raindancer.core.player;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The one thing in this package that touches the server.
 *
 * <p>Every rule about what is allowed, what would kill somebody and what is already the case lives
 * on the other side of this and is tested without a server. Everything here is one call.
 */
public interface PlayerAdminSink {

    /** How somebody is, or empty when they are not online. */
    Optional<PlayerState> stateOf(UUID who);

    void health(UUID who, double health);

    void food(UUID who, int food);

    /** @param lasting null for an effect that does not expire */
    void effect(UUID who, String effect, int level, Duration lasting);

    void clearEffect(UUID who, String effect);

    void clearAllEffects(UUID who);

    void allowFlight(UUID who, boolean allowed);

    void gamemode(UUID who, String mode);

    void kick(UUID who, String reason);

    void extinguish(UUID who);
}
