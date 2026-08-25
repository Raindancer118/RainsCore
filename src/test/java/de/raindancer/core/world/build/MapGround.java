package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A world as a map, which is all {@link Ground} ever needed to be. Shared by the build tests. */
final class MapGround implements Ground {

    private final Map<Spot, String> blocks = new HashMap<>();
    private final Set<Spot> unloaded = new HashSet<>();
    private final Set<Spot> refused = new HashSet<>();
    private String everywhereElse = "AIR";

    MapGround put(Spot spot, String material) {
        blocks.put(spot, material);
        return this;
    }

    MapGround fillWith(String material) {
        everywhereElse = material;
        return this;
    }

    MapGround unload(Spot spot) {
        unloaded.add(spot);
        return this;
    }

    /** A position the world will not take a block at — outside its height, or a material it lacks. */
    MapGround refuse(Spot spot) {
        refused.add(spot);
        return this;
    }

    @Override
    public String materialAt(Spot spot) {
        return blocks.getOrDefault(spot, everywhereElse);
    }

    @Override
    public boolean set(Spot spot, String material) {
        if (refused.contains(spot)) {
            return false;
        }
        blocks.put(spot, material);
        return true;
    }

    @Override
    public boolean isLoaded(Spot spot) {
        return !unloaded.contains(spot);
    }
}
