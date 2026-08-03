package de.raindancer.core.chunk;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import org.bukkit.Bukkit;
import de.raindancer.core.util.Scheduling;
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
                .thenApply(loaded -> loaded != null)
                .exceptionally(failure -> {
                    log.warn("Could not load {} ({})", chunk, failure.getMessage());
                    return false;
                });
    }

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
