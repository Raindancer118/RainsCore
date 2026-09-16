package de.raindancer.core.world.chunk;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Bukkit;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * The handful of lines that actually load a chunk.
 *
 * <p>Everything about who wants what and when it may go lives in {@link ChunkHolds} and is tested
 * without a server. This is the seam.
 */
public final class BukkitChunkLoader implements ChunkLoader {

    private static final LogChannel log = Log.of("chunks");

    private final org.bukkit.plugin.Plugin plugin;

    public BukkitChunkLoader(org.bukkit.plugin.Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isLoaded(ChunkAt chunk) {
        World world = Bukkit.getWorld(chunk.world());
        return world != null && world.isChunkLoaded(chunk.x(), chunk.z());
    }

    @Override
    public CompletableFuture<Boolean> load(ChunkAt chunk) {
        World world = Bukkit.getWorld(chunk.world());
        if (world == null) {
            // A world that is not loaded is not an error worth a stack trace: it is a warp somebody
            // set in a world that has since been removed, which the caller has to handle anyway.
            return CompletableFuture.completedFuture(false);
        }
        // getChunkAtAsync rather than getChunkAt: loading on the main thread stops the server for as
        // long as the disk takes, and generating stops it for a great deal longer. This is also what
        // makes the call safe under Folia, where the chunk belongs to a region and not to a thread.
        return world.getChunkAtAsync(chunk.x(), chunk.z(), true)
                .thenApply(loaded -> {
                    if (loaded != null) {
                        holdForAMoment(world, chunk);
                    }
                    return loaded != null;
                })
                .exceptionally(failure -> {
                    log.warn("Could not load {} ({})", chunk, failure.getMessage());
                    return false;
                });
    }

    /**
     * Keeps a freshly loaded chunk in memory long enough for whatever asked to read it.
     *
     * <h2>Why a ticket at all</h2>
     * {@code getChunkAtAsync} loads a chunk and leaves nothing holding it, and Paper is free to let it go
     * again before the next line reads a block. Found on a live server: the safety search saw every block
     * as "not loaded", refused them all, and {@code /dim nether} said "nowhere safe" beside solid ground.
     *
     * <h2>Why counted</h2>
     * A plugin has one ticket per chunk, not one per request. Two searches over the same chunk would
     * otherwise have the first one's release take the chunk away from the second.
     *
     * <p>Released on the chunk's own region thread, which is where Folia allows it.
     */
    private void holdForAMoment(World world, ChunkAt chunk) {
        if (held.merge(chunk, 1, Integer::sum) == 1) {
            world.addPluginChunkTicket(chunk.x(), chunk.z(), plugin);
        }
        Bukkit.getRegionScheduler().runDelayed(plugin, world, chunk.x(), chunk.z(), task -> {
            Integer left = held.computeIfPresent(chunk, (at, count) -> count <= 1 ? null : count - 1);
            if (left == null) {
                world.removePluginChunkTicket(chunk.x(), chunk.z(), plugin);
            }
        }, MOMENT_TICKS);
    }

    /** How long "a moment" is: long enough for a search that starts right away, and no longer. */
    private static final long MOMENT_TICKS = 100L;

    private final java.util.concurrent.ConcurrentHashMap<ChunkAt, Integer> held =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Turns the force-load flag on or off, on the thread that is allowed to.
     *
     * <p>Through the global scheduler rather than directly: on Folia, changing a world's chunk state
     * off the owning thread is caught and throws. {@link ChunkHolds} says it is safe from any
     * thread, so this is where that has to be made true rather than merely claimed.
     */
    @Override
    public void keepLoaded(ChunkAt chunk, boolean keep) {
        World world = Bukkit.getWorld(chunk.world());
        if (world == null) {
            return;
        }
        Scheduling.global(plugin, () -> {
            World still = Bukkit.getWorld(chunk.world());
            if (still != null) {
                still.setChunkForceLoaded(chunk.x(), chunk.z(), keep);
            }
        });
    }
}
