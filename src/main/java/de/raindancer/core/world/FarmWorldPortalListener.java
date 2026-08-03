package de.raindancer.core.world;

import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Optional;

/**
 * Keeps a farm world's portals inside the farm world.
 *
 * <h2>Why this exists</h2>
 * It is the entire reason a farm world has its own nether. Without it, a portal lit in the farm
 * world leads to the <em>main</em> nether: people mine there instead, and the farm world protects
 * nothing at all — which is the exact thing it was built to do.
 *
 * <p>Only portals in worlds we manage are touched. Every other portal on the server behaves as it
 * always did, which matters because a listener that quietly redirects somebody else's portals is
 * far worse than one that misses a few of ours.
 */
public final class FarmWorldPortalListener implements Listener {

    private final FarmWorlds worlds;

    public FarmWorldPortalListener(FarmWorlds worlds) {
        this.worlds = worlds;
    }

    /**
     * A player going through a nether or end portal.
     *
     * <p>{@code ignoreCancelled} because a portal another plugin has already cancelled is not ours
     * to un-cancel, and {@code HIGH} rather than {@code MONITOR} so the destination can still be
     * changed — a MONITOR handler that edits the event is the classic way to have no effect at all.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        Location from = event.getFrom();
        if (from == null || from.getWorld() == null) {
            return;
        }
        // Not ours: leave it exactly as it was.
        if (worlds.state().setOwning(from.getWorld().getName()).isEmpty()) {
            return;
        }

        WorldSet.Part wanted = destinationOf(event, from);
        if (wanted == null) {
            return;
        }
        Optional<Location> target = worlds.portalTarget(from, wanted);
        target.ifPresent(event::setTo);
    }

    /**
     * Which kind of world this portal leads to.
     *
     * <p>From the portal's own type where the server says, and otherwise from where the player is:
     * a nether portal in the nether leads back to the overworld, and one in the overworld leads to
     * the nether.
     */
    private WorldSet.Part destinationOf(PlayerPortalEvent event, Location from) {
        PortalType type = portalTypeOf(event);
        WorldSet.Part here = worlds.state().setOwning(from.getWorld().getName())
                .flatMap(set -> set.partOf(from.getWorld().getName()))
                .orElse(null);
        if (here == null) {
            return null;
        }
        if (type == PortalType.ENDER) {
            return here == WorldSet.Part.END ? WorldSet.Part.OVERWORLD : WorldSet.Part.END;
        }
        return here == WorldSet.Part.NETHER ? WorldSet.Part.OVERWORLD : WorldSet.Part.NETHER;
    }

    /**
     * The portal's type, as best the event will say.
     *
     * <p>Paper does not always know — an end portal and an end gateway both arrive as a teleport
     * with a cause rather than a type — so the cause is the fallback.
     */
    private static PortalType portalTypeOf(PlayerPortalEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL
                || event.getCause() == PlayerTeleportEvent.TeleportCause.END_GATEWAY) {
            return PortalType.ENDER;
        }
        return PortalType.NETHER;
    }
}
