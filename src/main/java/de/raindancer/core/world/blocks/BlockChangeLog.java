package de.raindancer.core.world.blocks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Which blocks changed in the last few seconds, and when — by {@link System#nanoTime()} unless told
 * otherwise. Safe from any thread (Folia changes blocks on many).
 */
final class BlockChangeLog {

    /** Past this many blocks a box is answered by walking the changes rather than the box. */
    private static final int WALK_LIMIT = 512;
    private static final int PRUNE_EVERY = 1024;

    private final long keepNanos;
    private final LongSupplier clock;
    private final Map<UUID, ConcurrentHashMap<Long, Long>> byWorld = new ConcurrentHashMap<>();
    private final AtomicInteger sincePrune = new AtomicInteger();

    BlockChangeLog(long keepNanos, LongSupplier clock) {
        this.keepNanos = keepNanos;
        this.clock = clock;
    }

    void changed(UUID world, int x, int y, int z) {
        byWorld.computeIfAbsent(world, ignored -> new ConcurrentHashMap<>()).put(pack(x, y, z), clock.getAsLong());
        if (sincePrune.incrementAndGet() >= PRUNE_EVERY) {
            sincePrune.set(0);
            prune();
        }
    }

    /** Whether any block in the inclusive box changed at or after {@code sinceNanos}. */
    boolean changedSince(UUID world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long sinceNanos) {
        ConcurrentHashMap<Long, Long> changes = byWorld.get(world);
        if (changes == null || changes.isEmpty()) {
            return false;
        }
        long since = Math.max(sinceNanos, clock.getAsLong() - keepNanos);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > WALK_LIMIT) {
            for (Map.Entry<Long, Long> change : changes.entrySet()) {
                long key = change.getKey();
                int x = unpackX(key);
                int y = unpackY(key);
                int z = unpackZ(key);
                if (change.getValue() - since >= 0 && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                    return true;
                }
            }
            return false;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Long at = changes.get(pack(x, y, z));
                    if (at != null && at - since >= 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    void prune() {
        long oldest = clock.getAsLong() - keepNanos;
        byWorld.values().forEach(changes -> changes.values().removeIf(at -> at - oldest < 0));
        byWorld.values().removeIf(Map::isEmpty);
    }

    int size() {
        return byWorld.values().stream().mapToInt(Map::size).sum();
    }

    void clear() {
        byWorld.clear();
    }

    // x and z in 26 bits each, y in 12: the whole of a vanilla world, and then some.
    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    static int unpackZ(long packed) {
        return (int) (packed << 26 >> 38);
    }

    static int unpackY(long packed) {
        return (int) (packed << 52 >> 52);
    }
}
