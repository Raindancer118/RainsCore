package de.raindancer.core.world.safety;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.Set;

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
     * Paper's own cached heightmap — one lookup, no per-block cost, and already kept up to date as
     * the world changes. Exactly what a search dropped in from the sky wants to seed itself with
     * rather than falling through open air one block-by-block {@code check()} at a time.
     */
    @Override
    public int highestSolidY(int x, int z) {
        return world.getHighestBlockYAt(x, z);
    }

    /**
     * The terrain itself, as opposed to a tree, a building or anything else standing on it.
     *
     * <p>A whitelist rather than a blacklist: a block a future version adds is ground nobody asked to
     * exclude only once it has actually been added here, and until then a random arrival lands
     * somewhere the search has to keep looking for — safer than the other way around, where a new kind
     * of leaf or a new kind of log would silently count as ground because nobody had listed it yet.
     */
    private static final Set<Material> NATURAL_GROUND = EnumSet.of(
            Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE,
            Material.DEEPSLATE, Material.TUFF, Material.CALCITE,
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.PODZOL, Material.MYCELIUM, Material.DIRT_PATH, Material.FARMLAND, Material.MUD,
            Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY,
            Material.SNOW_BLOCK, Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE,
            Material.NETHERRACK, Material.SOUL_SAND, Material.SOUL_SOIL, Material.BASALT,
            Material.BLACKSTONE, Material.END_STONE, Material.OBSIDIAN,
            Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM);

    @Override
    public boolean isNaturalGround(Spot spot) {
        if (!isLoaded(spot) || spot.y() < world.getMinHeight() || spot.y() >= world.getMaxHeight()) {
            return false;
        }
        return NATURAL_GROUND.contains(world.getBlockAt(spot.x(), spot.y(), spot.z()).getType());
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
