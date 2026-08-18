package de.raindancer.core.world.manage;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a player was, right before the last time their world actually changed.
 *
 * <h2>Why this is not {@code Returns}</h2>
 * {@code core.world.teleport.Returns} is what {@code /back} means — a place a module records
 * deliberately when it moves somebody, one instance per {@code Travel}, and a death outranks a
 * teleport there on purpose. This is neither: it is unconditional, server-wide, and owned once by
 * Core itself, because {@link WorldRegenerator} needs an answer to "where was this player a moment
 * ago" for absolutely anybody standing in a world about to be deleted — including somebody who never
 * went through a module's own {@code Travel} at all, just walked through a portal or a plugin's raw
 * teleport.
 *
 * <h2>Deliberately not saved to disk</h2>
 * Same reasoning as {@code Returns}: a location from before a restart describes a server that has
 * moved on. Memory only, forgotten on quit.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread — a teleport for one player and a quit for another can land on different
 * region threads under Folia.
 */
public final class WorldEntryPoints implements Listener {

    private final Map<UUID, Location> before = new ConcurrentHashMap<>();

    /**
     * Records where a player was the moment before a teleport that actually changes their world.
     *
     * <p>Only cross-world moves matter here: an in-world hop is not "where they were before entering
     * this world", it is just standing somewhere else in it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || to.getWorld() == null) {
            return;
        }
        if (from.getWorld().equals(to.getWorld())) {
            return;
        }
        before.put(event.getPlayer().getUniqueId(), from);
    }

    /** Where {@code player} was right before their last cross-world teleport, if anything recorded one. */
    public Optional<Location> before(UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(before.get(player));
    }

    /** Forgets one player — called from the plugin's own {@code PlayerQuitEvent} handler, the same
     *  place every other per-player cache here is told somebody left. */
    public void forget(UUID player) {
        if (player != null) {
            before.remove(player);
        }
    }

    /** Forgets everybody. For a plugin being disabled, or a test starting clean. */
    public void clear() {
        before.clear();
    }
}
