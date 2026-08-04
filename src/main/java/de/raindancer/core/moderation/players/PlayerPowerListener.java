package de.raindancer.core.moderation.players;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Where god mode, instakill and instant breaking stop being sets of ids and start mattering.
 *
 * <h2>The two priorities, and why they are opposite ends</h2>
 * <b>God mode is {@code LOWEST} and {@code ignoreCancelled = false}.</b> It has to run before anything
 * else decides what the damage should be, because the point is that there is no damage — and it must see
 * events another plugin has already cancelled, or "invulnerable" would quietly mean "invulnerable except
 * where something else got there first".
 *
 * <p><b>Instakill is {@code HIGHEST} and {@code ignoreCancelled = true}.</b> The opposite end, and for
 * the opposite reason: it must run <em>after</em> {@code Combat} and the land protection have had their
 * say, so a moderator with instakill on still cannot hit somebody inside a claim that forbids it. A
 * one-hit-kill that ignored protection would be the single most destructive thing in this library.
 *
 * <h2>Why hunger is in here</h2>
 * Because "cannot be hurt" that lets somebody starve is not what anybody means by god mode, and the
 * first bug report would be about the food bar rather than about damage.
 */
public final class PlayerPowerListener implements Listener {

    private final PlayerPowers powers;

    public PlayerPowerListener(PlayerPowers powers) {
        this.powers = powers;
    }

    /**
     * Nothing hurts them — fall, fire, drowning, mobs, other players, the void.
     *
     * <p>{@link EntityDamageEvent} rather than the by-entity one, so every source is covered by one
     * check. A version that only handled combat damage would be a god mode that lets somebody drown.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onAnyDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player hurt
                && powers.isInvulnerable(hurt.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Every block gives way at once — creative-mode breaking, in survival.
     *
     * <p><b>Why this is safe, and why it has to be done this way.</b> {@code setInstaBreak} changes how
     * long a block takes and <em>nothing else</em>. The game still fires {@link
     * org.bukkit.event.block.BlockBreakEvent} straight afterwards, so the land protection, the claim
     * flags and every other plugin get exactly the say they had before — a moderator with this on still
     * cannot break a block in somebody's claim, they simply fail to break it instantly.
     *
     * <p>The tempting alternative — breaking the block from here with {@code setType(AIR)} — would skip
     * that event entirely, which is a hole through every protection on the server dressed up as a
     * convenience. It is not implemented, deliberately.
     *
     * <p>{@code ignoreCancelled = true} for the same reason: a block-damage event another plugin has
     * already refused stays refused.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDamage(org.bukkit.event.block.BlockDamageEvent event) {
        if (powers.breaksInstantly(event.getPlayer().getUniqueId())) {
            event.setInstaBreak(true);
        }
    }

    /** And they do not get hungry either. See the class note. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player
                && powers.isInvulnerable(player.getUniqueId())
                // Only going down. Eating should still work — an invulnerable player who cannot fill
                // their own food bar is one who looks broken the moment god mode comes off.
                && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    /**
     * Whatever they hit dies.
     *
     * <p>{@code ignoreCancelled = true} is the important half: if the land protection or {@code Combat}
     * has refused this attack, it stays refused. Instakill makes a permitted hit lethal; it does not
     * make a forbidden hit permitted.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker
                && powers.killsInOneHit(attacker.getUniqueId())) {
            event.setDamage(PlayerPowers.INSTAKILL_DAMAGE);
        }
    }

    /**
     * Neither power outlives the session.
     *
     * <p>{@code MONITOR}, because nothing else needs to know and this must happen whatever else the quit
     * handlers decide. See {@link PlayerPowers} for why they are not persisted at all.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        powers.forget(event.getPlayer().getUniqueId());
    }
}
