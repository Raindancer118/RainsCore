package de.raindancer.core.world.safety;

/**
 * What is where — the one thing in this package that has to ask the world.
 *
 * <p>The seam. Everything about whether a spot is safe, and about finding a better one, is
 * arithmetic on {@link BlockKind} and is tested against a grid rather than a server. This interface
 * is where that stops.
 */
public interface Blocks {

    /** What is at this position. Never null; unloaded or out of the world is {@link BlockKind#UNKNOWN}. */
    BlockKind at(Spot spot);

    /** The lowest block a world has. */
    int lowestY();

    /** One above the highest block a world has. */
    int highestY();

    /**
     * Whether this position can be looked at without loading a chunk.
     *
     * <p>Asked before scanning a wide area: on Folia, and on any busy server, generating chunks to
     * answer "is this safe" is how a teleport becomes a two-second freeze for everybody. A scan that
     * runs out of loaded ground gives up rather than pulling the world in behind it.
     */
    default boolean isLoaded(Spot spot) {
        return true;
    }

    /**
     * The topmost non-air block in this column, if the implementation can answer in constant time.
     *
     * <p>Exists so a search that starts high above the ground — a scattered arrival dropped from
     * the sky, most notably — does not have to <em>discover</em> where the ground is one block at a
     * time. Every block-by-block check along the way costs a full {@code check()}, and falling
     * through open air with nothing solid below for a long way makes each of those checks itself
     * scan for how far down safety is — quadratic in the distance fallen, and the reason a search
     * seeded from the sky over ordinary terrain could take the better part of a minute before this
     * existed.
     *
     * <p>The default answers {@link #highestY()}, which is honest rather than clever: a caller that
     * gets no real heightmap simply falls back to walking down and finds out for itself, exactly as
     * it always did. {@link BukkitBlocks} overrides this with the server's own heightmap, which is
     * already a single cached lookup with no per-block cost at all.
     */
    default int highestSolidY(int x, int z) {
        return highestY();
    }

    /**
     * Whether the block here reads as natural ground — stone, dirt, sand and the like — rather than
     * something built or grown on top of it.
     *
     * <p>Default answers true, because most callers do not care: a warp somebody set on a wooden
     * platform is exactly as much "somewhere to arrive" as one on grass, and a class that second-guessed
     * that would be overruling the person who placed it. {@link BukkitBlocks} narrows this by material
     * for the one caller that does — see {@link SafeSpots#naturalGroundOnly}.
     */
    default boolean isNaturalGround(Spot spot) {
        return true;
    }
}
