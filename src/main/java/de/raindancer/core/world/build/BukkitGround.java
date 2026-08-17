package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * {@link Ground} backed by real Bukkit blocks — the same relationship
 * {@link de.raindancer.core.world.safety.BukkitBlocks} has to {@code Blocks}.
 *
 * <p>Reads and writes {@code getType()}/{@code setType(..., false)} — plain material, no block data,
 * matching {@link Ground}'s own contract. A caller that needs directional or connected state (a
 * fence gate's facing, a wall's post) applies it as a second, real-Bukkit pass after placement; that
 * is not expressible against {@link Ground} and is not supposed to be — this class is the seam
 * where "what block goes here" stops being testable, not where every last placement detail lives.
 */
public final class BukkitGround implements Ground {

    private final World world;

    public BukkitGround(World world) {
        this.world = world;
    }

    public static BukkitGround of(String worldName) {
        World found = Bukkit.getWorld(worldName);
        return found == null ? null : new BukkitGround(found);
    }

    @Override
    public String materialAt(Spot spot) {
        if (!isLoaded(spot) || spot.y() < world.getMinHeight() || spot.y() >= world.getMaxHeight()) {
            return null;
        }
        return world.getBlockAt(spot.x(), spot.y(), spot.z()).getType().name();
    }

    @Override
    public boolean set(Spot spot, String material) {
        if (!isLoaded(spot) || spot.y() < world.getMinHeight() || spot.y() >= world.getMaxHeight()) {
            return false;
        }
        Material resolved = Material.matchMaterial(material);
        if (resolved == null || !resolved.isBlock()) {
            return false;
        }
        world.getBlockAt(spot.x(), spot.y(), spot.z()).setType(resolved, false);
        return true;
    }

    @Override
    public boolean isLoaded(Spot spot) {
        return world.isChunkLoaded(spot.x() >> 4, spot.z() >> 4);
    }

    public World world() {
        return world;
    }
}
