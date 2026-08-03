package de.raindancer.core.world.safety;

import de.raindancer.core.world.chunk.ChunkAt;
import de.raindancer.core.world.chunk.ChunkHolds;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Is it safe to put a player there — asked the way a plugin actually needs to ask it.
 *
 * <h2>Why this sits on top of {@link SafeSpots}</h2>
 * Because of the one thing that makes the plain check useless in practice: a spot in an unloaded
 * chunk cannot be judged at all, and {@link SafeSpots} deliberately refuses to load anything, since
 * generating terrain to answer "is this warp safe" stops the server for everybody on it.
 *
 * <p>So the answer to "the chunk is not loaded" is not to shrug and it is not to load it on the main
 * thread. It is to bring the ground in first, off the main thread, and then judge — which is what
 * this does. A plugin calls {@link #findSafe} and gets a spot or an honest nothing; the loading, the
 * threading and the giving up are handled once here rather than badly in nine places.
 *
 * <h2>What it will not do</h2>
 * It never force-loads. A check is a look, and a look is not a reason to tick a chunk for the rest of
 * the server's life — that is {@link ChunkHolds#keep}, and it is a decision a plugin makes
 * deliberately with its name attached.
 *
 * <h2>Threads — read this before calling {@link #findSafe}</h2>
 * It is asynchronous, and you must <b>never</b> {@code join()} or {@code get()} it on the server
 * thread. Loading a chunk completes <em>on</em> that thread, so a thread waiting for it is a thread
 * blocking the very work it is waiting for: the server hangs until the watchdog kills it, with no
 * exception and nothing in the log to say why.
 *
 * <p>That is not hypothetical. It is how this paragraph came to be written — a live-server check
 * called {@code .join()} and the server stopped dead. Use {@code thenAccept}, and note that the
 * callback arrives on whatever thread finished the load, so hop back before touching the world:
 *
 * <pre>{@code
 * core.safety().findSafe(spot, 8).thenAccept(found -> Scheduling.global(plugin, () ->
 *         found.ifPresentOrElse(this::teleportTo, this::refuse)));
 * }</pre>
 */
public final class Safety {

    private final ChunkHolds chunks;
    private final Function<String, Blocks> blocksIn;

    /**
     * @param blocksIn how to read a world by name; null for a world that is not loaded
     */
    public Safety(ChunkHolds chunks, Function<String, Blocks> blocksIn) {
        this.chunks = chunks;
        this.blocksIn = blocksIn;
    }

    /** The checker for one world, or empty when there is no such world loaded. */
    public Optional<SafeSpots> in(String world) {
        Blocks blocks = world == null ? null : blocksIn.apply(world);
        return blocks == null ? Optional.empty() : Optional.of(new SafeSpots(blocks));
    }

    /**
     * What is wrong with a spot, without loading anything.
     *
     * <p>{@link Danger#NOT_LOADED} here means "ask {@link #findSafe} instead", not "unsafe".
     */
    public Danger check(Spot spot) {
        return in(spot == null ? null : spot.world())
                .map(spots -> spots.check(spot))
                .orElse(Danger.OUT_OF_THE_WORLD);
    }

    /**
     * Somewhere safe near this spot, loading whatever has to be loaded to find out.
     *
     * <p>The one a teleport should call. Empty means nowhere within the radius was safe, which a
     * caller must treat as a refusal — falling back to the original spot puts the player in the
     * place already known to be dangerous, which is the bug this whole package exists to stop.
     *
     * @param radius how far sideways to look; also how much world is pulled in, so keep it modest
     */
    public CompletableFuture<Optional<Spot>> findSafe(Spot around, int radius) {
        if (around == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        warnIfOnTheServerThread();
        return chunks.forAMoment(chunksAround(around, radius))
                .thenApply(ignored -> in(around.world())
                        .flatMap(spots -> spots.nearestSafe(around, radius)));
    }

    /** The same, with the checker configured first — for water, or for looking at the surroundings. */
    public CompletableFuture<Optional<Spot>> findSafe(Spot around, int radius,
                                                      java.util.function.Consumer<SafeSpots> setUp) {
        if (around == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        warnIfOnTheServerThread();
        return chunks.forAMoment(chunksAround(around, radius))
                .thenApply(ignored -> in(around.world()).flatMap(spots -> {
                    if (setUp != null) {
                        setUp.accept(spots);
                    }
                    return spots.nearestSafe(around, radius);
                }));
    }

    /**
     * Nothing, unless somebody is about to deadlock the server.
     *
     * <p>A plugin that calls this and then blocks on the answer will hang the server until the
     * watchdog kills it — silently, because a deadlock throws nothing. The one thing that can be
     * done from here is to make sure there is a line in the log naming the plugin that did it,
     * rather than a mystery freeze twenty minutes into a session.
     */
    private void warnIfOnTheServerThread() {
        if (org.bukkit.Bukkit.isPrimaryThread() && warnedAboutBlocking.compareAndSet(false, true)) {
            de.raindancer.core.platform.log.Log.of("safety").warn(
                    "findSafe was called on the server thread. That is fine — but if you then "
                            + "join() or get() the result you will deadlock the server, because the "
                            + "chunk load it is waiting for needs this same thread. Use "
                            + "thenAccept.");
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean warnedAboutBlocking =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Every chunk a search of this size could touch.
     *
     * <p>Worked out from the corners rather than a chunk per block: a radius of 32 is four thousand
     * positions and at most nine chunks, and asking for the same chunk four thousand times is how a
     * safety check becomes the slow part.
     */
    private List<ChunkAt> chunksAround(Spot spot, int radius) {
        int reach = Math.max(0, radius) + 1;
        int from = (spot.x() - reach) >> 4;
        int to = (spot.x() + reach) >> 4;
        int fromZ = (spot.z() - reach) >> 4;
        int toZ = (spot.z() + reach) >> 4;

        List<ChunkAt> needed = new ArrayList<>();
        for (int x = from; x <= to; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                needed.add(new ChunkAt(spot.world(), x, z));
            }
        }
        return needed;
    }
}
