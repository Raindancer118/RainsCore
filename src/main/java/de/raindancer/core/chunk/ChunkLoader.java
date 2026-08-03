package de.raindancer.core.chunk;

import java.util.concurrent.CompletableFuture;

/**
 * The one thing in this package that talks to the server.
 *
 * <p>The seam. Which chunks are held, by whom, and when one can actually be let go is bookkeeping
 * and is tested without a server; this is where that stops.
 */
public interface ChunkLoader {

    /** Whether a chunk is in memory right now. */
    boolean isLoaded(ChunkAt chunk);

    /**
     * Brings a chunk in, generating it if it has never existed.
     *
     * <p>Asynchronous because the alternative is not: loading a chunk on the main thread stops the
     * server for as long as the disk takes, and generating one stops it for a great deal longer.
     *
     * @return whether it is loaded; false when the world is gone or the load failed
     */
    CompletableFuture<Boolean> load(ChunkAt chunk);

    /**
     * Turns a chunk's force-load flag on or off.
     *
     * <p>This is written into the world's own data and survives a restart, which is exactly why
     * {@link ChunkHolds} exists to keep track of who asked for it.
     */
    void keepLoaded(ChunkAt chunk, boolean keep);
}
