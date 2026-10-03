package de.raindancer.core.world.locate;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A structure search that never holds a region for seconds: cells, a budget per tick, a stop the
 * moment nothing unsearched could be nearer, and answers remembered.
 */
class StructureLocatorTest {

    private final World world = mock(World.class);
    private final List<Runnable> nextTicks = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong();
    private final AtomicInteger searched = new AtomicInteger();

    {
        when(world.getName()).thenReturn("world");
    }

    /** A world with one structure at these block coordinates; every cell search costs 4 ms. */
    private StructureLocator.CellSearch oneStructureAt(double x, double z) {
        return (in, cell, keys) -> {
            searched.incrementAndGet();
            clock.addAndGet(Duration.ofMillis(4).toNanos());
            int chunkX = Math.floorDiv((int) x, 16);
            int chunkZ = Math.floorDiv((int) z, 16);
            boolean inside = Math.abs(chunkX - cell.centreChunkX()) <= cell.radiusChunks()
                    && Math.abs(chunkZ - cell.centreChunkZ()) <= cell.radiusChunks();
            return inside ? Optional.of(new StructureLocator.Hit(x, 70, z)) : Optional.empty();
        };
    }

    private StructureLocator locator(StructureLocator.CellSearch search) {
        return new StructureLocator(search, (origin, step) -> nextTicks.add(step), clock::get);
    }

    private <T> T runTicks(CompletableFuture<T> answer, int most) {
        for (int tick = 0; tick < most && !answer.isDone(); tick++) {
            nextTicksTaken++;
            List<Runnable> now = new ArrayList<>(nextTicks);
            nextTicks.clear();
            now.forEach(Runnable::run);
        }
        assertThat(answer).as("finished within " + most + " ticks").isDone();
        return answer.join();
    }

    @Test
    @DisplayName("a structure in the origin's own cell is answered at once, without searching further")
    void nearbyIsQuick() {
        CompletableFuture<Optional<Location>> answer = locator(oneStructureAt(20, 20))
                .nearest(new Location(world, 0, 64, 0), List.of("desert_pyramid"));

        assertThat(answer).isDone();
        assertThat(answer.join()).hasValueSatisfying(at -> assertThat(at.getX()).isEqualTo(20));
        assertThat(searched).hasValue(1);
    }

    @Test
    @DisplayName("a far one is found over several ticks, no tick spending more than its budget")
    void spreadOverTicks() {
        CompletableFuture<Optional<Location>> answer = locator(oneStructureAt(900, -600))
                .nearest(new Location(world, 0, 64, 0), List.of("minecraft:desert_pyramid"),
                        StructureLocator.Search.standard().budget(Duration.ofMillis(10)));

        assertThat(answer).as("not all in the calling tick").isNotDone();
        Optional<Location> found = runTicks(answer, 500);
        assertThat(found).hasValueSatisfying(at -> assertThat(at.getX()).isEqualTo(900));
        // 10 ms budget at 4 ms a cell is three cells a tick at most.
        assertThat(searched.get()).isLessThanOrEqualTo(3 * (nextTicksTaken + 1));
    }

    private int nextTicksTaken;

    @Test
    @DisplayName("nothing within reach is an empty answer, not an endless search")
    void nothingWithinReach() {
        CompletableFuture<Optional<Location>> answer = locator((in, cell, keys) -> Optional.empty())
                .nearest(new Location(world, 0, 64, 0), List.of("desert_pyramid"),
                        StructureLocator.Search.standard().within(30));

        assertThat(runTicks(answer, 1_000)).isEmpty();
    }

    @Test
    @DisplayName("the nearest wins even when a farther one was found first in the same ring")
    void nearestWins() {
        StructureLocator.CellSearch two = (in, cell, keys) -> {
            Optional<StructureLocator.Hit> near = oneStructureAt(-150, 0).search(in, cell, keys);
            return near.isPresent() ? near : oneStructureAt(160, 160).search(in, cell, keys);
        };
        CompletableFuture<Optional<Location>> answer = locator(two)
                .nearest(new Location(world, 0, 64, 0), List.of("village_plains"));

        assertThat(runTicks(answer, 200)).hasValueSatisfying(at -> assertThat(at.getX()).isEqualTo(-150));
    }

    @Test
    @DisplayName("a second search over the same ground is answered from memory")
    void remembered() {
        StructureLocator locator = locator(oneStructureAt(400, 0));
        runTicks(locator.nearest(new Location(world, 0, 64, 0), List.of("desert_pyramid")), 200);
        int first = searched.get();

        runTicks(locator.nearest(new Location(world, 0, 64, 0), List.of("minecraft:desert_pyramid")), 200);

        assertThat(searched.get()).as("same structures, same cells").isEqualTo(first);
        locator.forget("world");
        assertThat(locator.rememberedCells()).isZero();
    }

    @Test
    @DisplayName("cancelling the answer stops the search at its next step")
    void cancelStops() {
        CompletableFuture<Optional<Location>> answer = locator((in, cell, keys) -> {
            searched.incrementAndGet();
            clock.addAndGet(Duration.ofMillis(20).toNanos());
            return Optional.empty();
        }).nearest(new Location(world, 0, 64, 0), List.of("desert_pyramid"));
        answer.cancel(false);
        int before = searched.get();

        nextTicks.forEach(Runnable::run);

        assertThat(searched.get()).isEqualTo(before);
    }

    @Test
    @DisplayName("no world or no structure is an empty answer straight away")
    void nothingToSearch() {
        StructureLocator locator = locator(oneStructureAt(0, 0));

        assertThat(locator.nearest(new Location(null, 0, 0, 0), List.of("desert_pyramid")).join()).isEmpty();
        assertThat(locator.nearest(new Location(world, 0, 0, 0), List.of(" ")).join()).isEmpty();
        assertThat(searched).hasValue(0);
    }

    @Test
    @DisplayName("the spiral only settles when nothing unsearched could be nearer")
    void settlesOnlyWhenProven() {
        SpiralSearch<String> spiral = new SpiralSearch<>(8, 8, 4, 100);
        spiral.next();                                   // the origin's cell
        spiral.found(8 + 9 * 16, 8, "at the far edge");  // 144 blocks out
        assertThat(spiral.settled()).as("a ring further out could still be nearer").isFalse();
        spiral.found(8 + 20, 8, "close");
        assertThat(spiral.settled()).isTrue();
        assertThat(spiral.best()).contains("close");
    }
}
