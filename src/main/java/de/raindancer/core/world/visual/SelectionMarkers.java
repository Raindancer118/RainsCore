package de.raindancer.core.world.visual;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;

/**
 * The blocks somebody has clicked while marking a shape out, lit up and kept lit.
 *
 * <h2>Why the clicked blocks and not only the outline</h2>
 * An outline drawn between corners answers "what shape is this"; it does not answer "which block did
 * I actually click", and that is the question somebody asks when a corner is one off. So the clicked
 * block itself is swapped for a light-emitting one — <em>on the client only</em> — with a particle
 * beacon above it that grows a block taller per corner, so the order they went in is readable from a
 * distance and through terrain.
 *
 * <h2>Nothing here touches the world</h2>
 * {@link Player#sendBlockChange} and {@link Player#spawnParticle}: nobody else sees any of it, and
 * the real blocks are never changed. {@link #clear(Player)} sends the originals back — and a player
 * who logs out before that gets them from the server on the next join anyway, since the world never
 * differed.
 *
 * <p>Generic on purpose: it knows about columns and a height per column, not about claims or walls.
 * {@code claims-module} has its own older copy of this idea wired into its own types; this is the one
 * a second consumer can use, and where that copy should eventually land.
 */
public final class SelectionMarkers {

    /** How tall a beacon may grow, so the twentieth corner is not a pillar to the sky. */
    private static final int MAX_BEACON = 8;

    private static final class Marked {
        ScheduledTask task;
        final Map<Location, BlockData> replaced = new LinkedHashMap<>();
    }

    private final Plugin plugin;
    private final Map<UUID, Marked> byPlayer = new ConcurrentHashMap<>();

    public SelectionMarkers(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Lights up every corner marked so far, replacing whatever was shown before.
     *
     * @param heightAt what height a corner sits at, by index — the height it was clicked at, or the
     *                 ground, or the viewer's own; this class does not care which, only that there is
     *                 one
     */
    public void show(Player player, World world, List<Column> corners, IntUnaryOperator heightAt,
                     Material marker, Color colour) {
        clear(player);
        if (player == null || world == null || corners == null || corners.isEmpty()) {
            return;
        }
        if (!world.getUID().equals(player.getWorld().getUID())) {
            return;
        }

        Marked active = new Marked();
        byPlayer.put(player.getUniqueId(), active);

        List<Column> fixed = List.copyOf(corners);
        BlockData markerData = marker.createBlockData();

        // Grouped by chunk, because reading the block that is there belongs to the region that owns
        // it — on Folia, reading it from here is a crash rather than a wrong answer.
        Map<Long, List<Integer>> byChunk = new HashMap<>();
        for (int index = 0; index < fixed.size(); index++) {
            Column column = fixed.get(index);
            long key = (((long) (column.x() >> 4)) << 32) ^ (column.z() >> 4) & 0xffffffffL;
            byChunk.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
        }

        for (List<Integer> indices : byChunk.values()) {
            Column first = fixed.get(indices.get(0));
            Location anchor = new Location(world, first.x() + 0.5, 64, first.z() + 0.5);
            Scheduling.region(plugin, anchor, () -> {
                if (!player.isOnline() || byPlayer.get(player.getUniqueId()) != active) {
                    return;
                }
                for (int index : indices) {
                    Column column = fixed.get(index);
                    if (!world.isChunkLoaded(column.x() >> 4, column.z() >> 4)) {
                        continue;
                    }
                    Location at = new Location(world, column.x(), heightAt.applyAsInt(index), column.z());
                    active.replaced.put(at, world.getBlockAt(at).getBlockData());
                    player.sendBlockChange(at, markerData);
                }
            });
        }

        Particle.DustOptions dust = new Particle.DustOptions(colour, 1.3f);
        Particle.DustOptions firstDust = new Particle.DustOptions(Color.LIME, 1.6f);
        active.task = Scheduling.entityTimer(plugin, player, 1L, 12L, task -> {
            if (!player.isOnline() || byPlayer.get(player.getUniqueId()) != active) {
                task.cancel();
                return;
            }
            for (int index = 0; index < fixed.size(); index++) {
                Column column = fixed.get(index);
                int baseY = heightAt.applyAsInt(index);
                // Taller per corner, so which one is the fourth is answerable by looking.
                int beacon = 2 + Math.min(MAX_BEACON, index);
                for (int offset = 1; offset <= beacon; offset++) {
                    player.spawnParticle(Particle.DUST, column.x() + 0.5, baseY + offset + 0.5,
                            column.z() + 0.5, 1, 0, 0, 0, 0,
                            index == 0 ? firstDust : dust);
                }
            }
        });
    }

    /** Takes the markers away and sends the real blocks back. */
    public void clear(Player player) {
        if (player == null) {
            return;
        }
        Marked active = byPlayer.remove(player.getUniqueId());
        if (active == null) {
            return;
        }
        if (active.task != null) {
            active.task.cancel();
        }
        if (player.isOnline()) {
            active.replaced.forEach(player::sendBlockChange);
        }
    }

    public boolean isShowing(Player player) {
        return player != null && byPlayer.containsKey(player.getUniqueId());
    }

    public void clearAll() {
        for (UUID id : List.copyOf(byPlayer.keySet())) {
            Marked active = byPlayer.remove(id);
            if (active != null && active.task != null) {
                active.task.cancel();
            }
        }
    }
}
