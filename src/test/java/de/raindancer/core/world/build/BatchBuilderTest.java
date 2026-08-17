package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A wall/road build and its exact teardown, both driven by the same class — the property the whole
 * plan's "every action has an opposite" rule depends on.
 */
class BatchBuilderTest {

    private static final class FakeGround implements Ground {

        private final Map<Spot, String> blocks = new HashMap<>();
        private final Set<Spot> unloaded = new HashSet<>();
        private String everywhereElse = "GRASS_BLOCK";

        @Override
        public String materialAt(Spot spot) {
            return unloaded.contains(spot) ? null : blocks.getOrDefault(spot, everywhereElse);
        }

        @Override
        public boolean set(Spot spot, String material) {
            if (unloaded.contains(spot)) {
                return false;
            }
            blocks.put(spot, material);
            return true;
        }

        @Override
        public boolean isLoaded(Spot spot) {
            return !unloaded.contains(spot);
        }
    }

    @Test
    void placesEveryQueuedBlockAcrossSeveralBatches() {
        FakeGround ground = new FakeGround();
        List<BatchBuilder.Placement> queue = List.of(
                new BatchBuilder.Placement(new Spot("world", 0, 64, 0), "STONE_BRICKS"),
                new BatchBuilder.Placement(new Spot("world", 1, 64, 0), "STONE_BRICKS"),
                new BatchBuilder.Placement(new Spot("world", 2, 64, 0), "STONE_BRICKS"));
        BatchBuilder builder = new BatchBuilder(ground, queue);

        assertThat(builder.advance(2)).isEqualTo(2);
        assertThat(builder.isDone()).isFalse();
        assertThat(builder.advance(2)).isEqualTo(1);
        assertThat(builder.isDone()).isTrue();

        assertThat(ground.materialAt(new Spot("world", 0, 64, 0))).isEqualTo("STONE_BRICKS");
        assertThat(ground.materialAt(new Spot("world", 2, 64, 0))).isEqualTo("STONE_BRICKS");
    }

    @Test
    void teardownRestoresExactlyWhatWasThereBefore() {
        FakeGround ground = new FakeGround();
        Spot spot = new Spot("world", 5, 70, 5);
        ground.set(spot, "OAK_LOG");
        List<BatchBuilder.Placement> queue = List.of(new BatchBuilder.Placement(spot, "STONE_BRICKS"));

        BatchBuilder build = new BatchBuilder(ground, queue);
        build.advance(10);
        assertThat(ground.materialAt(spot)).isEqualTo("STONE_BRICKS");

        BuildSnapshot snapshot = build.snapshotSoFar();
        assertThat(snapshot.size()).isOne();

        BatchBuilder teardown = new BatchBuilder(ground, snapshot.asRestorePlacements().stream()
                .map(p -> new BatchBuilder.Placement(p.spot(), p.material()))
                .toList());
        teardown.advance(10);

        assertThat(ground.materialAt(spot)).isEqualTo("OAK_LOG");
    }

    @Test
    void skipsUnloadedSpotsWithoutStoppingTheBatch() {
        FakeGround ground = new FakeGround();
        Spot loaded = new Spot("world", 0, 64, 0);
        Spot unloaded = new Spot("world", 100, 64, 0);
        ground.unloaded.add(unloaded);
        List<BatchBuilder.Placement> queue = List.of(
                new BatchBuilder.Placement(unloaded, "STONE_BRICKS"),
                new BatchBuilder.Placement(loaded, "STONE_BRICKS"));

        BatchBuilder builder = new BatchBuilder(ground, queue);
        int placed = builder.advance(10);

        assertThat(placed).isEqualTo(1);
        assertThat(builder.isDone()).isTrue();
        assertThat(ground.materialAt(loaded)).isEqualTo("STONE_BRICKS");
    }
}
