package de.raindancer.core.pack;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * Offering the pack, and hearing what the client did with it.
 *
 * <p>Three events, no decisions: {@link ResourcePacks} owns whether a player should be sent it,
 * whether they have it already, and what a failure means. Keeping that split is what lets those
 * rules be tested without a server, which is where every one of them actually goes wrong.
 */
public final class PackListener implements Listener {

    private final ResourcePacks packs;
    private final boolean onJoin;

    /** @param onJoin whether a player is offered the pack as they arrive */
    public PackListener(ResourcePacks packs, boolean onJoin) {
        this.packs = packs;
        this.onJoin = onJoin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (onJoin) {
            packs.sendTo(event.getPlayer().getUniqueId());
        }
    }

    /**
     * Forgets a player as they leave.
     *
     * <p>Without this, somebody who declined — or whose client cleared its cache — would never be
     * offered the pack again for as long as the server stayed up, and the only fix a server owner
     * would find is a restart.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        packs.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        packs.record(event.getPlayer().getUniqueId(), translate(event.getStatus()));
    }

    /**
     * Bukkit's answer, as one of ours.
     *
     * <p>Bukkit has a dozen of these and most are stages of one download rather than outcomes.
     * Anything that is not an outcome leaves the player's state alone — {@code ACCEPTED} in
     * particular means "started downloading", and treating it as success is how a plugin ends up
     * drawing glyphs for somebody whose download then failed.
     */
    private static PackStatus translate(PlayerResourcePackStatusEvent.Status status) {
        return switch (status) {
            case SUCCESSFULLY_LOADED -> PackStatus.LOADED;
            case DECLINED -> PackStatus.DECLINED;
            case FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> PackStatus.FAILED;
            case ACCEPTED, DOWNLOADED -> PackStatus.SENT;
        };
    }
}
