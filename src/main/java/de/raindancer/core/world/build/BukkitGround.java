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
        // No physics: a wall going up block by block with physics on collapses its own gravel,
        // pops off its own torches and floods itself where it cut through water.
        block.setType(resolved, false);
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
