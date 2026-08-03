package de.raindancer.core.platform.bukkit;

import de.raindancer.core.ui.bossbar.BarViewers;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * {@link BarViewers} against the running server — the whole of the Bukkit half of boss bars.
 *
 * <p>A player who is not online is simply skipped. That is normal rather than exceptional: a bar is
 * routinely taken away from somebody precisely because they have just logged out.
 */
public final class BukkitBarViewers implements BarViewers {

    @Override
    public void show(UUID player, BossBar bar) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            online.showBossBar(bar);
        }
    }

    @Override
    public void hide(UUID player, BossBar bar) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            online.hideBossBar(bar);
        }
    }
}
