package de.raindancer.core.world.blocks;

import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Whether a block was put there by a player rather than grown or generated — so a quest or a reward for
 * mining ore cannot be farmed by placing silk-touched ore and mining it again.
 *
 * <p>Only materials some plugin {@link #watch watches} are remembered, in the chunk's own data, so a
 * builder's ten thousand stone bricks cost nothing. Read it before {@link EventPriority#MONITOR} in a
 * {@link BlockBreakEvent}: at MONITOR the mark is taken off.
 *
 * <p>A block moved by a piston keeps its mark. One taken by an explosion or fire loses it.
 */
public final class PlacedBlocks {

    static final NamespacedKey KEY = new NamespacedKey("rainscore", "placed");

    private record Watch(Plugin owner, Set<Material> materials) {
    }

    private static final List<Watch> watches = new CopyOnWriteArrayList<>();
    private static volatile Set<Material> watched = Set.of();

    private PlacedBlocks() {
    }

    /** Starts remembering where players place these. @return what to hand to {@link #unwatch} */
    public static synchronized Object watch(Plugin owner, Collection<Material> materials) {
        Watch watch = new Watch(owner, materials.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(materials)));
        watches.add(watch);
        rebuild();
        return watch;
    }

    public static synchronized void unwatch(Object handle) {
        watches.removeIf(each -> each == handle);
        rebuild();
    }

    public static synchronized int forgetFrom(ClassLoader loader) {
        int before = watches.size();
        watches.removeIf(each -> PluginCode.isFrom(each.owner(), loader));
        rebuild();
        return before - watches.size();
    }

    public static synchronized void clear() {
        watches.clear();
        rebuild();
    }

    private static void rebuild() {
        Set<Material> all = EnumSet.noneOf(Material.class);
        watches.forEach(each -> all.addAll(each.materials()));
        watched = Set.copyOf(all);
    }

    public static boolean watching(Material material) {
        return watched.contains(material);
    }

    /** Whether a player placed this block. Always false for a material nobody watches. */
    public static boolean isPlaced(Block block) {
        return watching(block.getType()) && PlacedSet.contains(read(block.getChunk()), packOf(block));
    }

    static void mark(Block block, boolean placed) {
        Chunk chunk = block.getChunk();
        int[] before = read(chunk);
        if (!placed && before.length == 0) {
            return;
        }
        int[] after = placed ? PlacedSet.add(before, packOf(block)) : PlacedSet.remove(before, packOf(block));
        if (after == before) {
            return;
        }
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        if (after.length == 0) {
            data.remove(KEY);
        } else {
            data.set(KEY, PersistentDataType.INTEGER_ARRAY, after);
        }
    }

    private static int[] read(Chunk chunk) {
        int[] stored = chunk.getPersistentDataContainer().get(KEY, PersistentDataType.INTEGER_ARRAY);
        return stored == null ? PlacedSet.EMPTY : stored;
    }

    private static int packOf(Block block) {
        return PlacedSet.pack(block.getX(), block.getY(), block.getZ());
    }

    /** Keeps the marks. Registered once by Core. */
    public static final class Listener implements org.bukkit.event.Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPlace(BlockPlaceEvent event) {
            if (watching(event.getBlockPlaced().getType())) {
                mark(event.getBlockPlaced(), true);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBreak(BlockBreakEvent event) {
            mark(event.getBlock(), false);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBurn(BlockBurnEvent event) {
            mark(event.getBlock(), false);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onExplode(EntityExplodeEvent event) {
            event.blockList().forEach(block -> mark(block, false));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onExplode(BlockExplodeEvent event) {
            event.blockList().forEach(block -> mark(block, false));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPush(BlockPistonExtendEvent event) {
            move(event.getBlocks(), event.getDirection());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPull(BlockPistonRetractEvent event) {
            move(event.getBlocks(), event.getDirection());
        }

        private static void move(List<Block> blocks, BlockFace direction) {
            List<Block> marked = new ArrayList<>();
            for (Block block : blocks) {
                if (isPlaced(block)) {
                    marked.add(block);
                }
            }
            // All off first, then all on: blocks in a row move into each other's places.
            marked.forEach(block -> mark(block, false));
            marked.forEach(block -> mark(block.getRelative(direction), true));
        }
    }
}
