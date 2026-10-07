package de.raindancer.core.moderation.players;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The doing half of {@link PlayerBody}: no decisions, only the server. */
public interface PlayerBodySink {

    Optional<BodyState> stateOf(UUID who);

    void air(UUID who, int air);

    /** Hurts them the way water does, so a death says "drowned". */
    void drowningDamage(UUID who, double amount);

    /**
     * Sets their velocity.
     *
     * @param relativeToLook z is "the way they face" and x "to their right", rather than world axes
     */
    void launch(UUID who, double x, double y, double z, boolean relativeToLook);

    void scale(UUID who, double scale);

    void walkSpeed(UUID who, float speed);

    void flySpeed(UUID who, float speed);

    void wipe(UUID who, Set<PlayerBody.Wipe> parts);

    void explode(UUID who, float power, boolean breakBlocks, boolean fire);

    void ignite(UUID who, int ticks);
}
