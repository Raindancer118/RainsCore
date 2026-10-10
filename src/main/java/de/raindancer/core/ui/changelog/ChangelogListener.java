package de.raindancer.core.ui.changelog;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.time.ZoneId;
import java.util.List;

/** Tells somebody coming back what changed while they were away. */
public final class ChangelogListener implements Listener {

    /** After the join messages and the welcome, so it is not scrolled away with them. */
    private static final long DELAY_TICKS = 20L * 3;

    private final Plugin plugin;
    private final Changelog changelog;

    public ChangelogListener(Plugin plugin, Changelog changelog) {
        this.plugin = plugin;
        this.changelog = changelog;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Asked now: by the time the task runs, the server counts this join as "played before".
        boolean returning = player.hasPlayedBefore();
        Scheduling.entityLater(plugin, player, DELAY_TICKS, () -> {
            if (!player.isOnline()) {
                return;
            }
            List<ChangelogEntry> news = changelog.joined(player.getUniqueId(), returning);
            if (!news.isEmpty()) {
                player.sendMessage(ChangelogView.render(news, "Since you were last here", ZoneId.systemDefault()));
            }
        });
    }
}
