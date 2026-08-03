package de.raindancer.core.chunk;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is keeping which chunks loaded, and when one can actually go.
 *
 * <h2>Why this is needed at all</h2>
 * Because a check on an unloaded chunk cannot answer. {@code SafeSpots} refuses to load anything —
 * generating terrain to answer "is this warp safe" would stop the server for everybody — so
 * something has to bring the ground in first, and something has to decide when it may go again.
 *
 * <h2>Why it counts holders instead of keeping a set</h2>
 * Two plugins can want the same chunk and only one of them be finished with it. A ghast line keeping
 * its landing pad loaded and a farm world keeping its spawn loaded may well be the same chunk, and
 * the ghast line letting go must not unload it under the farm world.
 *
 * <p>The other half is worse. A force-loaded chunk is written into the world's own data, so it
 * <em>survives a restart</em>: a plugin that forgets to let go leaves a server ticking chunks nobody
 * can account for, for ever, with nothing in any log to say why. Hence a name on every hold,
 * {@link #releaseAllFrom} when a plugin is disabled, and {@link #releaseAll} on the way out.
 *
 * <h2>The two ways to want a chunk</h2>
 * {@link #forAMoment} loads one and holds nothing — for a check that is about to happen. {@link
 * #keep} holds it until somebody says otherwise — for ground that has to stay put. Using the second
 * where the first would do is how a server ends up ticking half its map.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread.
 */
public final class ChunkHolds {

    private static final LogChannel log = Log.of("chunks");

    private final ChunkLoader loader;

    /** Who is holding what. A chunk with an empty set is removed, never left behind. */
    private final Map<ChunkAt, Set<String>> holders = new ConcurrentHashMap<>();

    public ChunkHolds(ChunkLoader loader) {
        this.loader = loader;
    }

    // ---------------------------------------------------------------------------- for a moment

    /**
     * Brings a chunk in for whatever is about to look at it, holding nothing.
     *
     * <p>What a safety check should use. The chunk stays in memory for as long as the server would
     * ordinarily keep it, which is long enough for the thing that asked, and it is not force-loaded —
     * a look is not a reason to tick a chunk for the rest of the server's life.
     *
     * @return whether it is loaded, once it is
     */
    public CompletableFuture<Boolean> forAMoment(ChunkAt chunk) {
        if (chunk == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (loader.isLoaded(chunk)) {
            return CompletableFuture.completedFuture(true);
        }
        return loader.load(chunk);
    }

    /** The same for every chunk given, answering when they are all in. */
    public CompletableFuture<Void> forAMoment(List<ChunkAt> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(chunks.stream()
                .map(this::forAMoment)
                .toArray(CompletableFuture[]::new));
    }

    // ---------------------------------------------------------------------------- for good

    /**
     * Keeps a chunk loaded until somebody lets it go.
     *
     * @param owner who wants it — a plugin's name, so a chunk that is never released has somebody's
     *              name on it rather than being a mystery in the world data
     * @return whether this changed anything; false if that owner already held it
     */
    public boolean keep(String owner, ChunkAt chunk) {
        if (chunk == null || owner == null || owner.isBlank()) {
            // Refused rather than held anonymously. A permanently loaded chunk with nobody's name on
            // it is precisely the leak this class exists to make findable.
            log.warn("A chunk was asked to be kept loaded with no owner; it was not.");
            return false;
        }
        String who = owner.trim();
        boolean[] first = {false};
        holders.compute(chunk, (at, current) -> {
            Set<String> set = current == null ? new LinkedHashSet<>() : current;
            first[0] = set.add(who) && set.size() == 1;
            return set;
        });
        if (first[0]) {
            loader.keepLoaded(chunk, true);
            log.info("{} is keeping {} loaded.", who, chunk);
            return true;
        }
        return false;
    }

    /**
     * Lets one owner's hold go.
     *
     * @return whether the chunk was actually released — false when somebody else still wants it, or
     *         when this owner was not holding it
     */
    public boolean release(String owner, ChunkAt chunk) {
        if (chunk == null || owner == null || owner.isBlank()) {
            return false;
        }
        String who = owner.trim();
        boolean[] last = {false};
        holders.computeIfPresent(chunk, (at, set) -> {
            if (!set.remove(who)) {
                return set;
            }
            last[0] = set.isEmpty();
            return set.isEmpty() ? null : set;
        });
        if (last[0]) {
            loader.keepLoaded(chunk, false);
            return true;
        }
        return false;
    }

    /**
     * Lets go of everything one plugin held — for a plugin being disabled.
     *
     * @return how many chunks were actually released
     */
    public int releaseAllFrom(String owner) {
        if (owner == null || owner.isBlank()) {
            return 0;
        }
        String who = owner.trim();
        int released = 0;
        for (ChunkAt chunk : List.copyOf(holders.keySet())) {
            if (release(who, chunk)) {
                released++;
            }
        }
        return released;
    }

    /**
     * Lets go of everything — for a shutdown.
     *
     * <p>Called on the way out whatever else happened, because the flag outlives the process.
     *
     * @return how many chunks were released
     */
    public int releaseAll() {
        int released = 0;
        for (ChunkAt chunk : List.copyOf(holders.keySet())) {
            holders.remove(chunk);
            loader.keepLoaded(chunk, false);
            released++;
        }
        return released;
    }

    // ---------------------------------------------------------------------------- looking

    /** Whether anybody is holding this chunk. */
    public boolean isHeld(ChunkAt chunk) {
        return chunk != null && holders.containsKey(chunk);
    }

    /** Who is holding it — so a chunk that will not go away has names attached. */
    public Set<String> holdersOf(ChunkAt chunk) {
        Set<String> set = chunk == null ? null : holders.get(chunk);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    /** Everything one plugin is holding. */
    public Set<ChunkAt> heldBy(String owner) {
        if (owner == null || owner.isBlank()) {
            return Set.of();
        }
        String who = owner.trim();
        return holders.entrySet().stream()
                .filter(entry -> entry.getValue().contains(who))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Every chunk being held, by anybody. */
    public Set<ChunkAt> all() {
        return Collections.unmodifiableSet(Set.copyOf(holders.keySet()));
    }

    public int size() {
        return holders.size();
    }
}
