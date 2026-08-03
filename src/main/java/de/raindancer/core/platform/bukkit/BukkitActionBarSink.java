package de.raindancer.core.platform.bukkit;

import de.raindancer.core.ui.actionbar.ActionBarSink;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * {@link ActionBarSink} against the running server.
 *
 * <p>A player who has logged out since the decision was made is simply not there; that is normal
 * rather than exceptional, so it is a missing player rather than a thrown exception.
 */
public final class BukkitActionBarSink implements ActionBarSink {

    @Override
    public void send(UUID player, Component message) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            online.sendActionBar(message);
        }
    }
}
