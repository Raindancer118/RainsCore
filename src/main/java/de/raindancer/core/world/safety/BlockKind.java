package de.raindancer.core.world.safety;

/**
 * What a block is, as far as standing in it is concerned.
 *
 * <h2>Why not just use {@code Material}</h2>
 * Because there are well over a thousand of them and this cares about six things. Reducing them once,
 * at the edge, means every rule about what is safe is arithmetic on this enum and can be tested
 * without a server — which matters, because the rules are where the bugs are and the server is where
 * they are expensive to find.
 *
 * <p>It also means a block added in a future version is {@link #UNKNOWN} rather than a crash, and
 * {@link #UNKNOWN} is treated as solid: refusing to teleport somebody into a block nobody has heard
 * of is the right way to be wrong.
 */
public enum BlockKind {

    /** Air, and everything you can stand inside — grass, torches, signs. */
    PASSABLE,

    /** Something you can stand on and cannot walk through. */
    SOLID,

    /** Water. Not fatal, but not somewhere to drop a player who was not expecting it. */
    WATER,

    /** Lava. */
    LAVA,

    /** Fire, cactus, magma, campfires, sweet berries, powder snow — it hurts to be here. */
    HARMFUL,

    /** A portal. Standing here means being sent somewhere else in a few seconds. */
    PORTAL,

    /**
     * Below the world, above it, or in a chunk nobody has loaded.
     *
     * <p>Deliberately its own answer rather than folded into "solid": a spot that cannot be checked
     * is not a spot that is safe, and the two have to be told apart to say so.
     */
    UNKNOWN;

    /** Whether a player can occupy this block without being inside something. */
    public boolean canStandIn() {
        return this == PASSABLE;
    }

    /** Whether this will hold a player up. */
    public boolean canStandOn() {
        return this == SOLID || this == UNKNOWN;
    }

    /** Whether being here costs health. */
    public boolean hurts() {
        return this == LAVA || this == HARMFUL;
    }
}
