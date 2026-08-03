package de.raindancer.core.safety;

/**
 * Why a spot is not safe.
 *
 * <p>A reason rather than a boolean, because "you cannot warp there" gets asked again and "you
 * cannot warp there — it is underwater" does not. It is also the difference between a server owner
 * moving a warp and one filing a bug.
 */
public enum Danger {

    /** Nothing wrong with it. */
    NONE("it is fine"),

    /** There is a block where the player would be. */
    INSIDE_A_BLOCK("there is a block in the way"),

    /** Nothing to stand on, and a long way down. */
    NOTHING_BELOW("there is nothing to stand on"),

    /** Lava, at the feet or under them. */
    LAVA("there is lava"),

    /** Fire, cactus, magma, a campfire, sweet berries, powder snow. */
    HURTS("standing there hurts"),

    /** Underwater. Survivable, and still not where somebody wants to arrive. */
    UNDERWATER("it is underwater"),

    /** A portal, which would move the player again a moment later. */
    PORTAL("it is inside a portal"),

    /** Below the world, above it, or too far up to survive the landing. */
    OUT_OF_THE_WORLD("it is outside the world"),

    /** The chunk is not loaded, so this could not be checked at all. */
    NOT_LOADED("that part of the world is not loaded"),

    /** Far enough to fall that the landing would hurt. */
    A_LONG_WAY_DOWN("it is a long way down"),

    /** The spot itself is fine, but there is lava right beside it. */
    LAVA_NEARBY("there is lava right next to it"),

    /** The spot itself is fine, but something that hurts is right beside it. */
    HURTS_NEARBY("there is something dangerous right next to it");

    private final String saying;

    Danger(String saying) {
        this.saying = saying;
    }

    /** A phrase that finishes "you cannot go there because …", in a player's words. */
    public String saying() {
        return saying;
    }

    public boolean isSafe() {
        return this == NONE;
    }
}
