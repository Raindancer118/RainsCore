package de.raindancer.core.world.safety;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Turning the server's thousand-odd materials into the six things standing somewhere cares about.
 *
 * <p>The seam for {@link SafeSpots}. Every rule about what is safe is on the other side of this and
 * is tested against a grid; all that is here is the reduction, and the judgement that anything this
 * does not recognise is treated as solid — refusing to put a player inside a block nobody has heard
 * of is the right way to be wrong about a block added in a future version.
 *
 * <h2>Threads</h2>
 * Reads blocks, so it must be asked on the thread that owns the region — the main thread on Paper,
 * the region's thread on Folia. It never loads a chunk: {@link #isLoaded} answers false instead, and
 * {@code ChunkHolds} is how a caller brings the ground in first.
 */
public final class BukkitBlocks implements Blocks {

    private final World world;

    public BukkitBlocks(World world) {
        this.world = world;
    }

    /** For a spot whose world is looked up by name; null when there is no such world. */
    public static BukkitBlocks of(String worldName) {
        World found = Bukkit.getWorld(worldName);
        return found == null ? null : new BukkitBlocks(found);
    }

    @Override
    public BlockKind at(Spot spot) {
        if (!isLoaded(spot) || spot.y() < world.getMinHeight() || spot.y() >= world.getMaxHeight()) {
            return BlockKind.UNKNOWN;
        }
        return kindOf(world.getBlockAt(spot.x(), spot.y(), spot.z()));
    }

    @Override
    public boolean isLoaded(Spot spot) {
        return world.isChunkLoaded(spot.x() >> 4, spot.z() >> 4);
    }

    @Override
    public int lowestY() {
        return world.getMinHeight();
    }

    @Override
    public int highestY() {
        return world.getMaxHeight();
    }

    /**
     * One block, as one of six answers.
     *
     * <p>The order matters: lava before liquids in general, harmful before passable, and the
     * catch-all last. A block that is both passable and harmful — fire, sweet berries, powder snow —
     * has to come out harmful or a player is teleported into it.
     */
    static BlockKind kindOf(Block block) {
        Material material = block.getType();
        if (material == Material.LAVA) {
            return BlockKind.LAVA;
        }
        if (material == Material.WATER || material == Material.BUBBLE_COLUMN) {
            return BlockKind.WATER;
        }
        if (isHarmful(material)) {
            return BlockKind.HARMFUL;
        }
        if (material == Material.NETHER_PORTAL || material == Material.END_PORTAL
                || material == Material.END_GATEWAY) {
            return BlockKind.PORTAL;
        }
        if (material.isAir() || block.isPassable()) {
            return BlockKind.PASSABLE;
        }
        return BlockKind.SOLID;
    }

    /** The blocks that cost health to stand in or on. */
    private static boolean isHarmful(Material material) {
        return switch (material) {
            case FIRE, SOUL_FIRE, CACTUS, MAGMA_BLOCK, SWEET_BERRY_BUSH, POWDER_SNOW,
                 CAMPFIRE, SOUL_CAMPFIRE, WITHER_ROSE, LAVA_CAULDRON, POINTED_DRIPSTONE -> true;
            default -> Tag.FIRE.isTagged(material);
        };
    }
}
