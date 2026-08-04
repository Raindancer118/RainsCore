package de.raindancer.core.world.spawn;

import de.raindancer.core.world.safety.Spot;

/**
 * Putting one creature into the world — the one thing in this package that asks the server.
 *
 * <p>The seam. What a wave <em>is</em>, and where each creature goes, are values and arithmetic; this
 * is where that stops.
 */
public interface Spawner {

    /**
     * Spawns one creature.
     *
     * @param type the entity type's name
     * @return whether it appeared. False for anything the world refused — an unloaded chunk, a type
     *         this server does not have, or a spot with no room
     */
    boolean spawn(Spot spot, String type);

    /**
     * Whether this position can be spawned at without loading a chunk.
     *
     * <p>A pack that arrives at the edge of what is loaded stops there rather than pulling the world
     * in behind it: the alternative is a command meant to take an instant freezing the server while it
     * generates ground for zombies nobody will ever see.
     */
    default boolean isLoaded(Spot spot) {
        return true;
    }
}
