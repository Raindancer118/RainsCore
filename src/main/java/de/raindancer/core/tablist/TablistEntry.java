package de.raindancer.core.tablist;

import java.util.UUID;

/**
 * One player, as the tablist sees them.
 *
 * <p>A value rather than a {@code Player}, for the reason everything else here is: it can be built
 * in a test, and it does not keep a reference to somebody who has logged out.
 *
 * @param world the world's name, not the world — see {@link de.raindancer.core.poi.Poi}
 * @param ping  their latency in milliseconds, for the bars at the right-hand end
 */
public record TablistEntry(UUID player, String name, String world, int ping) {

    public TablistEntry {
        name = name == null ? "" : name;
        world = world == null ? "" : world;
    }
}
