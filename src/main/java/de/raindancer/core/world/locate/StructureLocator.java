package de.raindancer.core.world.locate;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.generator.structure.Structure;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.StructureSearchResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * The nearest structure, found without holding a region for seconds.
 *
 * <h2>The problem</h2>
 * Paper's only structure search, {@code World.locateNearestStructure}, runs to the end in one call: on
 * a fresh world one desert pyramid took 8.4 s, and the server stood still for all of it. There is no
 * asynchronous version, because the search has to generate structure data as it goes.
 *
 * <h2>What this does instead</h2>
 * The same search, cut into small cells ({@link SpiralSearch}) — 9 × 9 chunks each by default — and
 * searched a few per tick, outwards from the origin, within a time budget per tick. It stops the
 * moment nothing still unsearched could beat what it has found, so a structure nearby is answered in a
 * tick or two. Each cell's answer is remembered (structures do not move), so a second search over the
 * same ground is instant.
 *
 * <pre>{@code
 * core.structures().nearest(player.getLocation(), List.of("minecraft:desert_pyramid"))
 *         .thenAccept(found -> found.ifPresentOrElse(
 *                 at -> point(player, at),
 *                 () -> chat.no(player, "There is none within reach.")));
 * }</pre>
 *
 * <h2>Threads</h2>
 * Every step runs on the region that owns the origin, and the answer is completed there. Call from
 * anywhere; never {@code join()} it on a server thread — it needs that thread to make progress.
 */
public final class StructureLocator {

    private static final LogChannel log = Log.of("locate");

    /** How a search goes: how far, how fine, and how much of each tick it may take. */
    public record Search(int maxRadiusChunks, int cellRadiusChunks, Duration budgetPerTick) {

        public Search {
            maxRadiusChunks = Math.clamp(maxRadiusChunks, 0, 2_000);
            cellRadiusChunks = Math.clamp(cellRadiusChunks, 0, 32);
            budgetPerTick = budgetPerTick == null || budgetPerTick.isNegative() || budgetPerTick.isZero()
                    ? Duration.ofMillis(10) : budgetPerTick;
        }

        /** Vanilla /locate's reach (100 chunks), 9 × 9-chunk cells, 10 ms of each tick. */
        public static Search standard() {
            return new Search(100, 4, Duration.ofMillis(10));
        }

        public Search within(int chunks) {
            return new Search(chunks, cellRadiusChunks, budgetPerTick);
        }

        public Search budget(Duration perTick) {
            return new Search(maxRadiusChunks, cellRadiusChunks, perTick);
        }
    }

    /** A structure found, in block coordinates. */
    public record Hit(double x, double y, double z) {
    }

    /** One cell's search. The server's own, or a test's. */
    @FunctionalInterface
    public interface CellSearch {
        Optional<Hit> search(World world, SpiralSearch.Cell cell, List<String> structureKeys);
    }

    /** Running a step on the origin's region one tick from now. */
    @FunctionalInterface
    public interface Stepper {
        void nextTick(Location origin, Runnable step);
    }

    /** How many cell answers are remembered. A few thousand cells is the whole of a busy server's searches. */
    private static final int REMEMBERED_CELLS = 4_096;

    private final CellSearch cells;
    private final Stepper stepper;
    private final LongSupplier nanos;
    private final Map<String, Optional<Hit>> remembered = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<Hit>> eldest) {
            return size() > REMEMBERED_CELLS;
        }
    };

    /** The server's own search, stepped on the origin's region. */
    public StructureLocator(Plugin plugin) {
        this(StructureLocator::serverSearch,
                (origin, step) -> Scheduling.regionLater(plugin, origin, 1, step), System::nanoTime);
    }

    public StructureLocator(CellSearch cells, Stepper stepper, LongSupplier nanos) {
        this.cells = Objects.requireNonNull(cells, "cells");
        this.stepper = Objects.requireNonNull(stepper, "stepper");
        this.nanos = Objects.requireNonNull(nanos, "nanos");
    }

    /** The nearest of any of these structures within vanilla /locate's reach. */
    public CompletableFuture<Optional<Location>> nearest(Location origin, Collection<String> structureKeys) {
        return nearest(origin, structureKeys, Search.standard());
    }

    /**
     * The nearest of any of these structures.
     *
     * @param structureKeys {@code minecraft:village_plains} or just {@code village_plains}; several
     *                      mean "whichever is nearest"
     * @return the structure's position, or empty when there is none within reach. Cancelling the
     *         future stops the search at its next step.
     */
    public CompletableFuture<Optional<Location>> nearest(Location origin, Collection<String> structureKeys,
                                                          Search search) {
        CompletableFuture<Optional<Location>> answer = new CompletableFuture<>();
        World world = origin == null ? null : origin.getWorld();
        List<String> keys = normalised(structureKeys);
        if (world == null || keys.isEmpty()) {
            answer.complete(Optional.empty());
            return answer;
        }
        Search how = search == null ? Search.standard() : search;
        SpiralSearch<Hit> spiral = new SpiralSearch<>(origin.getX(), origin.getZ(), how.cellRadiusChunks(),
                how.maxRadiusChunks());
        Location from = origin.clone();
        step(from, world, keys, how, spiral, answer);
        return answer;
    }

    private void step(Location origin, World world, List<String> keys, Search how, SpiralSearch<Hit> spiral,
                      CompletableFuture<Optional<Location>> answer) {
        if (answer.isDone()) {
            return;     // cancelled by whoever asked
        }
        long until = nanos.getAsLong() + how.budgetPerTick().toNanos();
        try {
            do {
                Optional<SpiralSearch.Cell> next = spiral.next();
                if (next.isEmpty()) {
                    answer.complete(spiral.best().map(hit -> new Location(world, hit.x(), hit.y(), hit.z())));
                    return;
                }
                SpiralSearch.Cell cell = next.get();
                searchCell(world, cell, keys).ifPresent(hit -> spiral.found(hit.x(), hit.z(), hit));
            } while (nanos.getAsLong() < until);
        } catch (RuntimeException failure) {
            log.warn(failure, "A structure search for {} failed after {} cell(s).", keys, spiral.cellsSearched());
            answer.completeExceptionally(failure);
            return;
        }
        stepper.nextTick(origin, () -> step(origin, world, keys, how, spiral, answer));
    }

    private Optional<Hit> searchCell(World world, SpiralSearch.Cell cell, List<String> keys) {
        String key = world.getName() + "|" + String.join(",", keys) + "|" + cell.centreChunkX() + ","
                + cell.centreChunkZ() + "," + cell.radiusChunks();
        synchronized (remembered) {
            Optional<Hit> known = remembered.get(key);
            if (known != null) {
                return known;
            }
        }
        Optional<Hit> found = cells.search(world, cell, keys);
        synchronized (remembered) {
            remembered.put(key, found);
        }
        return found;
    }

    /** How many cells are remembered — for a health check. */
    public int rememberedCells() {
        synchronized (remembered) {
            return remembered.size();
        }
    }

    /** Forgets every remembered answer — after a world has been regenerated, say. */
    public void forget() {
        synchronized (remembered) {
            remembered.clear();
        }
    }

    /** Forgets what was remembered about one world, by name. */
    public void forget(String worldName) {
        if (worldName == null) {
            return;
        }
        String prefix = worldName + "|";
        synchronized (remembered) {
            remembered.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    private static List<String> normalised(Collection<String> keys) {
        if (keys == null) {
            return List.of();
        }
        List<String> clean = new ArrayList<>();
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String lower = key.trim().toLowerCase(Locale.ROOT);
            clean.add(lower.contains(":") ? lower : "minecraft:" + lower);
        }
        return clean.stream().distinct().sorted().toList();
    }

    /** One cell, by the server's own search, bounded to that cell. */
    private static Optional<Hit> serverSearch(World world, SpiralSearch.Cell cell, List<String> keys) {
        Location centre = new Location(world, cell.centreChunkX() * 16 + 8, 64, cell.centreChunkZ() * 16 + 8);
        Hit nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (String key : keys) {
            NamespacedKey id = NamespacedKey.fromString(key);
            Structure structure = id == null ? null : Registry.STRUCTURE.get(id);
            if (structure == null) {
                continue;
            }
            StructureSearchResult result = world.locateNearestStructure(centre, structure, cell.radiusChunks(), false);
            if (result != null) {
                Location at = result.getLocation();
                double distance = at.distanceSquared(centre);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = new Hit(at.getX(), at.getY(), at.getZ());
                }
            }
        }
        return Optional.ofNullable(nearest);
    }
}
