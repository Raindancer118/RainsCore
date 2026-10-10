package de.raindancer.core.world.blocks;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.List;

/**
 * Where blocks changed in the last few seconds — broken, placed, blown up, pushed, melted, grown,
 * fallen — whoever or whatever changed them. For code that must judge something against the world
 * as it was a moment ago: a client's move was made before a block under it was mined away, and the
 * server only reads it afterwards.
 *
 * <p>Fed by the server's own block events (registered once by Core). A plugin that changes blocks
 * without firing one can report them itself with {@link #changed(Block)}. Fluids flowing and
 * redstone toggling are not recorded.
 */
public final class BlockChanges {

    /** How long a change is remembered. */
    public static final long KEEP_NANOS = 5_000_000_000L;

    static final BlockChangeLog LOG = new BlockChangeLog(KEEP_NANOS, System::nanoTime);

    private BlockChanges() {
    }

    /**
     * Whether any block in the inclusive box changed at or after {@code sinceNanos} (a
     * {@link System#nanoTime()} reading). Never further back than {@link #KEEP_NANOS}.
     */
    public static boolean changedSince(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                       long sinceNanos) {
        return LOG.changedSince(world.getUID(), minX, minY, minZ, maxX, maxY, maxZ, sinceNanos);
    }

    public static void changed(Block block) {
        LOG.changed(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    public static void clear() {
        LOG.clear();
    }

    private static void changed(List<Block> blocks) {
        blocks.forEach(BlockChanges::changed);
    }

    private static void changedStates(List<BlockState> states) {
        for (BlockState state : states) {
            LOG.changed(state.getWorld().getUID(), state.getX(), state.getY(), state.getZ());
        }
    }

    /** Records the changes. Registered once by Core. */
    public static final class Listener implements org.bukkit.event.Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBreak(BlockBreakEvent event) {
            changed(event.getBlock());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPlace(BlockPlaceEvent event) {
            if (event instanceof BlockMultiPlaceEvent multi) {
                changedStates(multi.getReplacedBlockStates());
            } else {
                changed(event.getBlockPlaced());
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onExplode(BlockExplodeEvent event) {
            changed(event.blockList());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onExplode(EntityExplodeEvent event) {
            changed(event.blockList());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPush(BlockPistonExtendEvent event) {
            moved(event.getBlock(), event.getBlocks(), event.getDirection());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPull(BlockPistonRetractEvent event) {
            moved(event.getBlock(), event.getBlocks(), event.getDirection());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBurn(BlockBurnEvent event) {
            changed(event.getBlock());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onFade(BlockFadeEvent event) {
            changed(event.getBlock());
        }

        /** Also snow, ice, concrete, obsidian, and everything that spreads. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onForm(BlockFormEvent event) {
            changed(event.getBlock());
        }

        /** Falling sand and gravel, endermen, trampled farmland, silverfish, withers. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onEntityChange(EntityChangeBlockEvent event) {
            changed(event.getBlock());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDecay(LeavesDecayEvent event) {
            changed(event.getBlock());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onGrow(BlockGrowEvent event) {
            changed(event.getBlock());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onTree(StructureGrowEvent event) {
            changedStates(event.getBlocks());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onFertilize(BlockFertilizeEvent event) {
            changedStates(event.getBlocks());
        }

        /** Blocks that lost what held them up: torches, rails, sugar cane, scaffolding. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDestroy(com.destroystokyo.paper.event.block.BlockDestroyEvent event) {
            changed(event.getBlock());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPrime(TNTPrimeEvent event) {
            changed(event.getBlock());
        }

        private static void moved(Block piston, List<Block> blocks, BlockFace direction) {
            changed(piston);
            changed(piston.getRelative(direction));
            for (Block block : blocks) {
                changed(block);
                changed(block.getRelative(direction));
            }
        }
    }
}
