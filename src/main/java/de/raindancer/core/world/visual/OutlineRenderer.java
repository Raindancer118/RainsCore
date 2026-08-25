package de.raindancer.core.world.visual;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import de.raindancer.core.world.geometry.Polyline;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Drawing a shape in the air so the person marking it can see what they have.
 *
 * <h2>Shown to one player, always</h2>
 * {@link Player#spawnParticle} rather than {@link World#spawnParticle}: a preview is a thing the
 * person marking is looking at, and drawn to the world it is a light show everybody within render
 * distance has to watch somebody else's wall get planned.
 *
 * <p>Capped at {@value #MAX_POINTS} particles per frame. An outline is a preview, and a five-hundred
 * block wall drawn a particle per block, twice a second, is more work than building it.
 */
public final class OutlineRenderer {

    private static final int MAX_POINTS = 400;
    private static final long PERIOD_TICKS = 10L;

    private final Plugin plugin;
    private final Map<UUID, ScheduledTask> live = new ConcurrentHashMap<>();

    public OutlineRenderer(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Draws whatever {@code corners} says, again every half second, until {@link #stop(Player)}.
     *
     * <p>The corners are a supplier rather than a list because the shape is being marked <em>while
     * this runs</em>: handed a list, the preview would show what was marked when it started and
     * never move again.
     */
    public void showLive(Player player, World world, Supplier<List<Column>> corners,
                         IntSupplier baseY, Particle.DustOptions dust) {
        stop(player);
        UUID id = player.getUniqueId();
        ScheduledTask task = Scheduling.entityTimer(plugin, player, PERIOD_TICKS, PERIOD_TICKS, scheduled -> {
            if (!player.isOnline()) {
                scheduled.cancel();
                live.remove(id);
                return;
            }
            draw(player, world, corners.get(), baseY.getAsInt(), dust);
        });
        if (task != null) {
            live.put(id, task);
        }
    }

    /** One frame of whatever this shape looks like right now. */
    public void draw(Player player, World world, List<Column> corners, int baseY, Particle.DustOptions dust) {
        if (corners == null || corners.size() < 2) {
            return;
        }
        List<Column> outline = corners.size() >= 3
                ? new ColumnPolygon(corners).outlineColumns()
                : new Polyline(corners).orderedColumns();

        int step = Math.max(1, outline.size() / MAX_POINTS);
        for (int i = 0; i < outline.size(); i += step) {
            Column column = outline.get(i);
            Location at = new Location(world, column.x() + 0.5, baseY + 1.2, column.z() + 0.5);
            player.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
        }
    }

    public void stop(Player player) {
        if (player == null) {
            return;
        }
        ScheduledTask task = live.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    public void stopAll() {
        live.values().forEach(ScheduledTask::cancel);
        live.clear();
    }
}
