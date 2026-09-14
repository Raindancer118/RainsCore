package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;

/**
 * What is where, and what may be changed — the one thing in this package that asks the world.
 *
 * <p>The seam, and the same one {@code world.safety} draws: everything about the <em>shape</em> of a
 * vein is arithmetic on {@link Spot} and is tested against a map rather than a server, and this
 * interface is where that stops.
 *
 * <p>Materials are names rather than {@code Material} for the reason they are everywhere else in
 * Core: resolving one needs the server's registry, and a value type with no registry in it is what
 * lets the interesting half be tested.
 */
public interface Ground {

    /** The material name at this position, upper case. Unloaded or outside the world is {@code null}. */
    String materialAt(Spot spot);

    /**
     * Puts a block there.
     *
     * @return whether it was placed. False for anything the world refused — outside its height, in an
     *         unloaded chunk, or a material this server does not have
     */
    boolean set(Spot spot, String material);

    /**
     * Whether this position can be touched without loading a chunk.
     *
     * <p>Asked before every placement. Generating chunks to bury ore in is how a command meant to take
     * an instant becomes a freeze for everybody on the server, and a vein that stops at the edge of
     * what is loaded is the right answer — the person running it is standing there looking at it.
     */
    default boolean isLoaded(Spot spot) {
        return true;
    }

    /**
     * Which biome this position is in, lower case and without its namespace — {@code "taiga"},
     * {@code "mangrove_swamp"} — or {@code null} where that cannot be answered.
     *
     * <p>Here because what a thing is <em>built from</em> often should follow where it is built: a
     * trestle bridge in a mangrove swamp made of oak looks imported, and one made of the wood growing
     * beside it looks like somebody who lives there built it. A name rather than the enum for the
     * same reason materials are names: resolving one needs the server's registry, and a value with no
     * registry in it is what lets the interesting half be tested.
     */
    default String biomeAt(Spot spot) {
        return null;
    }
}
