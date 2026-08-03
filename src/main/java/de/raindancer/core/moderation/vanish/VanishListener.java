package de.raindancer.core.moderation.vanish;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Keeping the promise across joins and leaves.
 *
 * <p>The two moments vanish usually breaks. Somebody who joins has to be hidden from the person who
 * just arrived — the new player has never been told to hide them — and somebody hidden must not have
 * their arrival announced. Both are one line and both are always forgotten.
 */
public final class VanishListener implements Listener {

    private final Plugin plugin;
    private final Vanish vanish;
    private final String seeVanishedPermission;

    public VanishListener(Plugin plugin, Vanish vanish, String seeVanishedPermission) {
        this.plugin = plugin;
        this.vanish = vanish;
        this.seeVanishedPermission = seeVanishedPermission;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        vanish.maySeeVanished(joining.getUniqueId(),
                seeVanishedPermission != null && joining.hasPermission(seeVanishedPermission));

        if (vanish.isVanished(joining.getUniqueId())) {
            // Quietly: their own arrival must not be announced, and they have to be hidden again
            // from everybody, since a fresh connection knows nothing about who was hidden.
            event.joinMessage(null);
        }
        // Everybody already hidden has to be hidden from the person who just arrived. Without this
        // the newest player is the one person who can see every vanished moderator on the server.
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(joining) && vanish.isVanished(other.getUniqueId())
                    && !vanish.maySeeVanished(joining.getUniqueId())) {
                joining.hidePlayer(plugin, other);
            }
        }
        if (vanish.isVanished(joining.getUniqueId())) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(joining) && !vanish.maySeeVanished(viewer.getUniqueId())) {
                    viewer.hidePlayer(plugin, joining);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        if (vanish.isVanished(event.getPlayer().getUniqueId())) {
            event.quitMessage(null);
        }
        // Whether they may see hidden players is a fact about this session, not about them. Being
        // hidden is not, and deliberately survives — see Vanish#forgetSession.
        vanish.forgetSession(event.getPlayer().getUniqueId());
    }
}
