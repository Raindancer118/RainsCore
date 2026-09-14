package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * {@link Ground} against a real world — the one implementation that needs a server, so everything
 * else in this package does not.
 */
public final class BukkitGround implements Ground {

    private final World world;

    private BukkitGround(World world) {
        this.world = world;
    }

    /** @return a ground for this world, or {@code null} when the server has no such world loaded */
    public static BukkitGround of(String worldName) {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new BukkitGround(world);
    }

    public static BukkitGround of(World world) {
        return world == null ? null : new BukkitGround(world);
    }

    @Override
    public String materialAt(Spot spot) {
        if (!belongsHere(spot) || !isLoaded(spot)) {
            return null;
        }
        return world.getBlockAt(spot.x(), spot.y(), spot.z()).getType().name();
    }

    @Override
    public boolean set(Spot spot, String material) {
        if (!belongsHere(spot) || !isLoaded(spot)) {
            return false;
        }
        Material resolved = Material.matchMaterial(material);
        if (resolved == null || !resolved.isBlock()) {
            return false;
        }
        Block block = world.getBlockAt(spot.x(), spot.y(), spot.z());
        // Physics off, with one deliberate exception.
        //
        // Off, because a wall going up block by block with physics on collapses its own gravel, pops
        // off its own torches, and — the one that matters most — lets the water it just cut through
        // flow back into the hole. An underwater tunnel is only dry because its shell went up without
        // telling the sea about it.
        //
        // The exception is the blocks whose *shape* is decided by that same update: a fence, a wall,
        // a pane, a bar. Placed without one, every one of them stays a lone post that never notices
        // its neighbour — which is what a bridge railing looked like on the test server, a row of
        // separate stumps instead of a rail. None of them is a fluid, none of them falls, and none of
        // them is what holds water back, so an update on these costs nothing the rest of the rule was
        // protecting.
        block.setType(resolved, connects(resolved));
        return true;
    }

    @Override
    public boolean isLoaded(Spot spot) {
        if (!belongsHere(spot)) {
            return false;
        }
        if (spot.y() < world.getMinHeight() || spot.y() >= world.getMaxHeight()) {
            return false;
        }
        return world.isChunkLoaded(spot.x() >> 4, spot.z() >> 4);
    }

    @Override
    public String biomeAt(Spot spot) {
        if (!belongsHere(spot) || !isLoaded(spot)) {
            return null;
        }
        return world.getBiome(spot.x(), spot.y(), spot.z()).getKey().getKey();
    }

    /**
     * Whether this block's appearance depends on its neighbours.
     *
     * <p>By name rather than by a list of materials: every wood adds a fence, every stone adds a wall,
     * and a hard-coded list is wrong by the next version.
     */
    private static boolean connects(Material material) {
        String name = material.name();
        return name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE") || name.endsWith("_WALL")
                || name.endsWith("_PANE") || name.equals("IRON_BARS") || name.equals("CHAIN")
                || name.equals("LADDER");
    }

    public World world() {
        return world;
    }

    /**
     * Whether this position is even about this world.
     *
     * <p>The coordinates alone cannot say: every world has a block at 0/64/0, so a spot from the
     * nether would otherwise be read and written here as though it belonged.
     */
    private boolean belongsHere(Spot spot) {
        return spot != null && world.getName().equals(spot.world());
    }
}
