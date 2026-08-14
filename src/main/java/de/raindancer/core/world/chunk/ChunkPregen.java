package de.raindancer.core.world.chunk;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Walking a region, one throttled batch at a time, so every chunk in it has been generated before
 * anybody has to wait for it to be.
 *
 * <h2>What this is not</h2>
 * Not a hold. {@link ChunkHolds#forAMoment} and this both call {@link ChunkLoader#load}, and neither
 * force-loads — generating the terrain once and letting the server unload it again on its own
 * schedule is the point. A module that also needs the result to <em>stay</em> in memory asks
 * {@link ChunkHolds#keep} for that, separately, with its own name on it.
 *
 * <h2>What this does not know</h2>
 * Nothing about "rtp" or "farm world". A module builds the list of {@link ChunkAt} that matters to
 * it — a scatter radius, a freshly regenerated world's border — and hands it here; this only ever
 * asks "the next {@code batchSize}, please" and reports how far it has got. Two modules wanting
 * "warm this region up before anybody needs it" is exactly the shared need that makes this Core's
 * rather than either module's own.
 *
 * <h2>Why a step, not a loop</h2>
 * A method that ran until the region was done would be a method a caller invokes once and forgets —
 * which is the "generate everything right now" cost this exists to spread out. {@link #step} does
 * one batch and returns; pacing how often it is called — a fixed size each tick, or fewer when the
 * server is already busy — is the caller's decision, driven through
 * {@code de.raindancer.core.platform.util.Scheduling} the way any other repeating work on this server is.
 *
 * <h2>Thread safety</h2>
 * Not safe from multiple threads at once. A pregeneration run belongs to whatever timer is driving
 * it, the same as any other single piece of scheduled work; it does not need to defend against a
 * second caller stepping it concurrently; it needs a caller that does not do that.
 */
public final class ChunkPregen {

    private static final LogChannel log = Log.of("chunks");

    /** Where a run stands. */
    public enum State {
        /** Still walking the region. */
        RUNNING,
        /** Not walking it right now, but not given up either — {@link #resume()} carries on. */
        PAUSED,
        /** Nothing left to do — either the region is fully generated, or {@link #cancel()} was called. */
        DONE
    }

    /**
     * A snapshot of where a run stands, safe to hold onto and read later — it does not change
     * underneath whoever asked for it, unlike the run itself.
     *
     * @param total how many distinct chunks this run was ever given
     * @param done  how many have been asked of the loader so far
     * @param state where the run stands right now
     */
    public record Progress(int total, int done, State state) {
    }

    private final ChunkLoader loader;
    private final int total;
    private final Deque<ChunkAt> remaining;
    private volatile State state;

    /**
     * @param region every chunk to generate. Duplicates are kept only once — asking for the same
     *               chunk twice is not two chunks of work.
     */
    public ChunkPregen(ChunkLoader loader, List<ChunkAt> region) {
        this.loader = loader;
        this.remaining = new ArrayDeque<>(new LinkedHashSet<>(region));
        this.total = remaining.size();
        this.state = remaining.isEmpty() ? State.DONE : State.RUNNING;
    }

    /** Where this run stands right now. */
    public Progress progress() {
        return new Progress(total, total - remaining.size(), state);
    }

    /**
     * Asks the loader for up to {@code batchSize} more chunks from the region, and waits for all of
     * them to answer.
     *
     * <p>Paused or already done, this does nothing and completes with zero straight away — a caller
     * driving this off a timer does not have to check {@link #progress()} first every time.
     *
     * @return how many chunks were actually asked for this step — less than {@code batchSize} on the
     *         last step, zero once there is nothing left
     */
    public CompletableFuture<Integer> step(int batchSize) {
        if (state != State.RUNNING || batchSize <= 0) {
            return CompletableFuture.completedFuture(0);
        }
        List<ChunkAt> batch = new java.util.ArrayList<>(Math.min(batchSize, remaining.size()));
        for (int taken = 0; taken < batchSize; taken++) {
            ChunkAt next = remaining.poll();
            if (next == null) {
                break;
            }
            batch.add(next);
        }
        if (batch.isEmpty()) {
            state = State.DONE;
            return CompletableFuture.completedFuture(0);
        }
        List<CompletableFuture<Boolean>> loading = batch.stream().map(loader::load).toList();
        return CompletableFuture.allOf(loading.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            if (remaining.isEmpty()) {
                state = State.DONE;
                log.info("Finished pregenerating {} chunk(s).", total);
            }
            return batch.size();
        });
    }

    /** Stops stepping without losing what is left — {@link #resume()} carries on from here. */
    public void pause() {
        if (state == State.RUNNING) {
            state = State.PAUSED;
        }
    }

    /** Carries on after {@link #pause()}. Does nothing once {@link #cancel()} has been called. */
    public void resume() {
        if (state == State.PAUSED) {
            state = remaining.isEmpty() ? State.DONE : State.RUNNING;
        }
    }

    /**
     * Gives up on whatever is left, for good.
     *
     * <p>Unlike {@link #pause}, this cannot be undone by {@link #resume}: the remaining chunks are
     * dropped, not merely set aside. For a server owner who decided the region was the wrong one
     * partway through, rather than somebody who will come back to it.
     */
    public void cancel() {
        remaining.clear();
        state = State.DONE;
    }
}
