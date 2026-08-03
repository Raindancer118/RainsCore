package de.raindancer.core.world.protection;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/** Protects the world itself: breaking, placing, buckets, fire and hanging entities. */
public final class BlockProtectionListener implements Listener {

    private final Land land;

    public BlockProtectionListener(Land land) {
        this.land = land;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!land.allow(event.getPlayer(), event.getBlock().getLocation(), LandAction.BREAK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!land.allow(event.getPlayer(), event.getBlock().getLocation(), LandAction.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        // Beds and doors occupy two blocks; both halves must be allowed.
        for (var state : event.getReplacedBlockStates()) {
            if (!land.allow(event.getPlayer(), state.getLocation(), LandAction.BUILD)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (!land.allow(event.getPlayer(), event.getBlock().getLocation(), LandAction.BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block target = event.getBlock();
        if (!land.allow(event.getPlayer(), target.getLocation(), LandAction.BUCKETS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!land.allow(event.getPlayer(), event.getBlock().getLocation(), LandAction.BUCKETS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        if (!land.allow(event.getPlayer(), event.getHarvestedBlock().getLocation(),
                LandAction.FARMLAND)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Player igniter = event.getPlayer();
        if (igniter == null) {
            return;
        }
        if (!land.allow(igniter, event.getBlock().getLocation(), LandAction.IGNITE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (!land.allow(player, event.getEntity().getLocation(), LandAction.ITEM_FRAMES)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (!(event.getRemover() instanceof Player player)) {
            return;
        }
        if (!land.allow(player, event.getEntity().getLocation(), LandAction.ITEM_FRAMES)) {
            event.setCancelled(true);
        }
    }

    /** Trampling farmland fires as a physical interaction, not as a block break. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.PHYSICAL) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        if (block.getType() == Material.FARMLAND || block.getType() == Material.TURTLE_EGG) {
            if (!land.allowSilently(event.getPlayer(), block.getLocation(), LandAction.FARMLAND)) {
                event.setCancelled(true);
            }
        }
    }
}
