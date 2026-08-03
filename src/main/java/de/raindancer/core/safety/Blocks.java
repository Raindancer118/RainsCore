package de.raindancer.core.safety;

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
}
