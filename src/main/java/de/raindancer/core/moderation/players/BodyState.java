package de.raindancer.core.moderation.players;

/** What {@link PlayerBody} needs to know about somebody to decide. Speeds are the game's raw values. */
public record BodyState(int air, int maxAir, double health, double scale, float walkSpeed, float flySpeed,
                        int fireTicks) {
}
