package de.raindancer.core.world.teleport;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.function.BooleanSupplier;

/**
 * The three things that end a warm-up: walking off, being hurt, and logging out.
 *
 * <h2>Why the host registers this rather than {@link Travel} doing it</h2>
 * Because a plugin that wants a warm-up nobody can be knocked out of — an arena, a staff teleport —
 * should be able to have one, and a library that registers its own listeners is a library whose
 * behaviour cannot be switched off. Register it once, beside the {@code Travel} it belongs to:
 *
 * <pre>{@code
 * Travel travel = new Travel(this, core.safety());
 * getServer().getPluginManager().registerEvents(new TravelListener(travel), this);
 * }</pre>
 *
 * <p>The wording of each cancellation is the plugin's, through its {@code TravelWatcher}. What is
 * here is only <em>when</em>.
 */
public final class TravelListener implements Listener {

    private final Travel travel;

    /**
     * Where travellers came from, held directly rather than reached through {@link Travel}.
     *
     * <p>Because forgetting it is this class's job and not {@code Travel}'s: a journey ending is not
     * a session ending, and {@code Travel.forget} is also called when a countdown is retired
     * mid-wait — somebody interrupted there still wants {@code /back} to mean where they set off
     * from. What must not survive is a player logging out, which is exactly what this listens for.
     */
    private final Returns returns;

    /** Whether leaving the block gives up on the trip, read fresh on every move. */
    private final BooleanSupplier moveCancels;

    /** Whether being hurt gives up on the trip, read fresh on every hit. */
    private final BooleanSupplier hurtCancels;

    public TravelListener(Travel travel) {
        this(travel, true);
    }

    /**
     * @param hurtCancels false for a warm-up that a mob cannot interrupt. Worth thinking about: a
     *                    server with mobs at spawn and a five-second warm-up has a {@code /warp}
     *                    that nobody can complete, and the report reads "warping is broken"
     */
    public TravelListener(Travel travel, boolean hurtCancels) {
        this(travel, () -> true, () -> hurtCancels);
    }

    /**
     * Both switchable, and both read fresh every time rather than fixed when this is built — for a
     * host whose {@code cancel-on-move} and {@code cancel-on-damage} are reloadable settings, not a
     * choice made once at startup.
     *
     * @param moveCancels whether leaving the block gives up on the trip, asked again on every move
     * @param hurtCancels whether being hurt gives up on the trip, asked again on every hit
     */
    public TravelListener(Travel travel, BooleanSupplier moveCancels, BooleanSupplier hurtCancels) {
        this.travel = travel;
        this.returns = travel.cameFrom();
        this.moveCancels = moveCancels;
        this.hurtCancels = hurtCancels;
    }

    /**
     * Moving off the block cancels.
     *
     * <p>{@code MONITOR} and only looking: this decides nothing about the movement itself, it only
     * notices. The block rather than the position, because a player standing perfectly still is not
     * still — see {@link Departure#isAwayFrom}.
     *
     * <p>The cheap check comes first. This fires several times a second for every player on the
     * server, and almost none of them are going anywhere.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!travel.isTravelling(player.getUniqueId())) {
            return;
        }
        if (event.getTo().getBlockX() == event.getFrom().getBlockX()
                && event.getTo().getBlockY() == event.getFrom().getBlockY()
                && event.getTo().getBlockZ() == event.getFrom().getBlockZ()
                && event.getTo().getWorld() == event.getFrom().getWorld()) {
            return;   // turning on the spot, or breathing
        }
        if (!moveCancels.getAsBoolean()) {
            return;
        }
        if (travel.pending().hasMoved(player.getUniqueId(), Travel.spotOf(event.getTo()))) {
            travel.cancel(player, TravelReason.MOVED);
        }
    }

    /** Being hurt cancels, unless this one was built — or set — not to. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (!hurtCancels.getAsBoolean() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        travel.cancel(player, TravelReason.HURT);
    }

    /**
     * Logging out forgets, silently.
     *
     * <p>Silently on purpose: there is nobody to tell, and a watcher trying to message a player who
     * has gone is the sort of thing that logs an exception every time somebody quits mid-warp.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        travel.forget(event.getPlayer().getUniqueId());
        // A waypoint from before somebody logged out is a lie: the world has been running without
        // them and the reason they were moved is long over. Kept, it is also an entry per player who
        // has ever been teleported on this server.
        returns.forget(event.getPlayer().getUniqueId());
    }
}
