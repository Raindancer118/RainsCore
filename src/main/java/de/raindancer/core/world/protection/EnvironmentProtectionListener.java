package de.raindancer.core.world.protection;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.Optional;

/**
 * Enforces the environmental flags: explosions, fire, decay, grief, pistons, fluids, weather and the
 * player comfort flags (fall damage, hunger, keep inventory).
 * <p>
 * Every handler exits early when the flag's policy is {@code DISABLED}, so an admin who removed a flag
 * really does get vanilla behaviour with no overhead.
 */
public final class EnvironmentProtectionListener implements Listener {


    private final Land land;

    public EnvironmentProtectionListener(Land land) {
        this.land = land;
    }

    /**
     * Whether the flag forbids this at that block.
     * <p>
     * Asks the land service rather than looking the claim up directly: the block may be on a town's open
     * street, where there is no claim at all but the town still has rules. A listener that gave up on
     * "no claim here" would walk straight past exactly the land a town exists to govern.
     */
    private boolean denied(Location location, LandFlag flag) {
        if (!land.landFlags().isEnforced(flag)) {
            return false;
        }
        return !land.landFlags().isAllowedAt(location, flag);
    }

    /**
     * The same question as {@link #denied}, but about a creature rather than a block.
     * <p>
     * A player's claim is resolved the way the border tracker resolves it, tolerance included. Asking
     * for their exact block instead means somebody standing on their own roof — inside the claim for
     * every other purpose — loses the protection the flag promises them.
     * <p>
     * The victim's standing in the claim picks the value: an owner who keeps fall damage on for visitors
     * but off for themselves gets exactly that.
     */
    private boolean deniedFor(Entity victim, LandFlag flag) {
        if (!land.landFlags().isEnforced(flag)) {
            return false;
        }
        if (victim instanceof Player player) {
            // The border tracker's answer, tolerance included, so somebody on their own roof keeps the
            // protection the flag promises them.
            ProtectedArea tracked = land.areaAround(player).orElse(null);
            return !land.landFlags().isAllowedForTracked(tracked, player.getLocation(), flag, player);
        }
        return !land.landFlags().isAllowedFor(victim, flag);
    }

    // ------------------------------------------------------------ explosions

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.EXPLOSIONS)) {
            return;
        }
        // Remove only the blocks inside protected claims instead of cancelling the whole blast, so an
        // explosion outside a claim still works normally right up to the border.
        event.blockList().removeIf(block -> denied(block.getLocation(), LandFlag.EXPLOSIONS));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.EXPLOSIONS)) {
            return;
        }
        event.blockList().removeIf(block -> denied(block.getLocation(), LandFlag.EXPLOSIONS));
    }

    // ------------------------------------------------------------ fire

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (denied(event.getBlock().getLocation(), LandFlag.FIRE_SPREAD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (event.getSource().getType() != Material.FIRE && event.getSource().getType() != Material.SOUL_FIRE) {
            return;
        }
        if (denied(event.getBlock().getLocation(), LandFlag.FIRE_SPREAD)) {
            event.setCancelled(true);
        }
    }

    /** Lightning, lava and fire spread ignitions have no player behind them. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() != null) {
            return;
        }
        if (denied(event.getBlock().getLocation(), LandFlag.FIRE_SPREAD)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ decay, weather

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeafDecay(LeavesDecayEvent event) {
        if (denied(event.getBlock().getLocation(), LandFlag.LEAF_DECAY)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        Material type = event.getNewState().getType();
        if (type != Material.SNOW && type != Material.ICE && type != Material.FROSTED_ICE) {
            return;
        }
        if (denied(event.getBlock().getLocation(), LandFlag.SNOW_ICE_FORM)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        Material type = event.getBlock().getType();
        if (type != Material.SNOW && type != Material.ICE && type != Material.FROSTED_ICE) {
            return;
        }
        if (denied(event.getBlock().getLocation(), LandFlag.SNOW_ICE_FORM)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ mob grief

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        LandFlag flag = griefFlagFor(event.getEntity());
        if (flag == null) {
            return;
        }
        if (denied(event.getBlock().getLocation(), flag)) {
            event.setCancelled(true);
        }
    }

    /**
     * Which grief flag governs this entity changing a block, or {@code null} when none does.
     * <p>
     * Sand, gravel, anvils and concrete powder become a {@link FallingBlock} entity, and both leaving the
     * old spot and settling into the new one arrive as an {@link EntityChangeBlockEvent}. That is plain
     * gravity rather than a mob chewing on somebody's build, so no flag applies — treating it as grief
     * left falling blocks frozen in place inside every claim that had mob grief switched off.
     */
    static LandFlag griefFlagFor(Entity entity) {
        if (entity instanceof Player || entity instanceof FallingBlock) {
            return null;
        }
        return entity instanceof Enderman ? LandFlag.ENDERMAN_GRIEF : LandFlag.MOB_GRIEF;
    }

    // ------------------------------------------------------------ pistons and fluids

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistonCrossesBorder(event.getBlock(), event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonCrossesBorder(event.getBlock(), event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    /** True when a piston outside a claim tries to move blocks that belong to it. */
    private boolean pistonCrossesBorder(Block piston, java.util.List<Block> moved) {
        if (!land.landFlags().isEnforced(LandFlag.PISTONS_FROM_OUTSIDE)) {
            return false;
        }
        Optional<ProtectedArea> pistonClaim = land.areaAt(piston.getLocation());
        for (Block block : moved) {
            Optional<ProtectedArea> blockClaim = land.areaAt(block.getLocation());
            if (blockClaim.isEmpty()) {
                continue;
            }
            boolean sameClaim = pistonClaim.isPresent() && pistonClaim.get().id().equals(blockClaim.get().id());
            if (!sameClaim && !land.flags().isAllowed(blockClaim.get(), LandFlag.PISTONS_FROM_OUTSIDE)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (!land.landFlags().isEnforced(LandFlag.FLUIDS_FROM_OUTSIDE)) {
            return;
        }
        Optional<ProtectedArea> target = land.areaAt(event.getToBlock().getLocation());
        if (target.isEmpty()) {
            return;
        }
        Optional<ProtectedArea> source = land.areaAt(event.getBlock().getLocation());
        boolean sameClaim = source.isPresent() && source.get().id().equals(target.get().id());
        if (!sameClaim && !land.flags().isAllowed(target.get(), LandFlag.FLUIDS_FROM_OUTSIDE)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ visitor comfort

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();

        // Explosion damage to creatures is deliberately separate from EXPLOSIONS, which only governs
        // block damage: a claim may want fireworks and TNT mining without visitors getting blown up.
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            if (event.getEntity() instanceof LivingEntity
                    && deniedFor(event.getEntity(), LandFlag.EXPLOSION_DAMAGE)) {
                event.setCancelled(true);
            }
            return;
        }

        if (cause != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (deniedFor(player, LandFlag.FALL_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        // Only block food loss; eating must always work.
        if (event.getFoodLevel() >= player.getFoodLevel()) {
            return;
        }
        if (deniedFor(player, LandFlag.HUNGER)) {
            event.setCancelled(true);
        }
    }

    /**
     * Keeps a player's inventory when they die inside a claim that promises it.
     * <p>
     * The flag reads "items drop on death", so <em>denying</em> it is what keeps the inventory — off means
     * vanilla. Experience is kept along with the items: dying with your gear but without the levels to
     * repair it is not what anybody means by "keep inventory".
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!deniedFor(player, LandFlag.ITEM_DROP_ON_DEATH)) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }
}
