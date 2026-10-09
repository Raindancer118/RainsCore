package de.raindancer.core.social.presence;

import org.bukkit.Statistic;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Notes the name everybody joins under, and starts counting their playtime the first time Core sees them. */
public final class PresenceListener implements Listener {

    private final Playtime playtime;
    private final KnownNames names;

    public PresenceListener(Playtime playtime, KnownNames names) {
        this.playtime = playtime;
        this.names = names;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        names.seen(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        if (!playtime.isKnown(event.getPlayer().getUniqueId())) {
            playtime.seed(event.getPlayer().getUniqueId(), event.getPlayer().getStatistic(Statistic.PLAY_ONE_MINUTE));
        }
    }
}
