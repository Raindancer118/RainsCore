package de.raindancer.core.ui.bossbar;

import net.kyori.adventure.bossbar.BossBar;

import java.util.UUID;

/**
 * Showing and hiding one bar for one player — the whole of the Bukkit half.
 *
 * <p>A seam, so every rule in {@link BossBars} — the cap, the ranking, who is in an audience and
 * what happens when they leave it — is tested without a server. May throw: a player can log out
 * between the decision and the packet, and {@link BossBars} expects that.
 */
public interface BarViewers {

    void show(UUID player, BossBar bar);

    void hide(UUID player, BossBar bar);
}
