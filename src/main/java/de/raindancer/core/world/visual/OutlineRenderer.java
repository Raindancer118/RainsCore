package de.raindancer.core.world.visual;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.geometry.ColumnPolygon;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Draws a column outline in the world as particles, for one player at a time.
 *
 * <p>Generalised from {@code claims-module}'s own border-drawing (its {@code visual.OutlineGeometry}
 * / {@code BorderVisualizer}): the corner glow a claim shows while it is being marked out, and the
 * "show this border for a few seconds" preview a menu button offers, are both exactly this — a set
 * of columns, a colour, a duration. Written against plain {@link Particle#DUST} rather than a raw
 * packet layer: every server this runs on is Paper, and {@code World#spawnParticle} is the
 * supported, version-proof way to show one to a single player.
 */
public final class OutlineRenderer {

    private final Plugin plugin;
    private final Map<UUID, ScheduledTask> live = new HashMap<>();

    public OutlineRenderer(Plugin plugin) {
        this.plugin = plugin;
    }

    /** One flash of dust along the outline, right now. */
    public void showOnce(Player player, World world, Collection<ColumnPolygon.Column> outline,
                         int y, Particle.DustOptions color) {
        for (ColumnPolygon.Column column : outline) {
            Location at = new Location(world, column.x() + 0.5, y + 0.2, column.z() + 0.5);
            player.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, color);
        }
    }

    /**
     * Keeps redrawing the outline every few ticks until {@link #stop(Player)} is called — for a live
     * corner glow while somebody is still marking a shape out, where the outline itself changes as
     * they add vertices.
     */
    public void showLive(Player player, World world, Supplier<List<ColumnPolygon.Column>> outline,
                         IntSupplier y, Particle.DustOptions color) {
        stop(player);
        ScheduledTask task = Scheduling.entityTimer(plugin, player, 0L, 5L, ignored -> {
            if (!player.isOnline()) {
                return;
            }
            showOnce(player, world, outline.get(), y.getAsInt(), color);
        });
        live.put(player.getUniqueId(), task);
    }

    /** Stops the live redraw for this player, if one is running. Safe to call when none is. */
    public void stop(Player player) {
        ScheduledTask task = live.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }
}
