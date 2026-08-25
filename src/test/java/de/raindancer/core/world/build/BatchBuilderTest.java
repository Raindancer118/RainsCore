package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placing a lot of blocks a few at a time, and being able to put every one of them back.
 *
 * <p>The property everything here exists for: <strong>what the snapshot holds is exactly what was
 * changed</strong>. A snapshot with an entry too many restores a block that was never touched; one
 * with an entry too few leaves a hole in whatever was there before.
 */
class BatchBuilderTest {

    private static final String WORLD = "world";

    private static List<BatchBuilder.Placement> row(int length, String material) {
        List<BatchBuilder.Placement> placements = new ArrayList<>();
        for (int x = 0; x < length; x++) {
            placements.add(new BatchBuilder.Placement(new Spot(WORLD, x, 64, 0), material));
        }
        return placements;
    }

    @Test
    @DisplayName("nothing is placed until it is advanced")
    void placesNothingUpFront() {
        MapGround ground = new MapGround();

        BatchBuilder builder = new BatchBuilder(ground, row(10, "STONE"));

        assertThat(builder.total()).isEqualTo(10);
        assertThat(builder.isDone()).isFalse();
        assertThat(ground.materialAt(new Spot(WORLD, 0, 64, 0))).isEqualTo("AIR");
    }

    @Test
    @DisplayName("one advance places exactly as many as it was asked for")
    void advancesInBatches() {
        MapGround ground = new MapGround();
        BatchBuilder builder = new BatchBuilder(ground, row(10, "STONE"));

        builder.advance(4);

        assertThat(ground.materialAt(new Spot(WORLD, 3, 64, 0))).isEqualTo("STONE");
        assertThat(ground.materialAt(new Spot(WORLD, 4, 64, 0))).isEqualTo("AIR");
        assertThat(builder.isDone()).isFalse();
    }

    @Test
    @DisplayName("advancing past the end finishes rather than running off it")
    void finishes() {
        MapGround ground = new MapGround();
        BatchBuilder builder = new BatchBuilder(ground, row(3, "STONE"));

        builder.advance(100);

        assertThat(builder.isDone()).isTrue();
        assertThat(builder.advance(100)).isZero();
    }

    @Test
    @DisplayName("the snapshot holds what was there before, so it can be put back exactly")
    void snapshotRestoresWhatWasThere() {
        MapGround ground = new MapGround().fillWith("GRASS_BLOCK");
        BatchBuilder builder = new BatchBuilder(ground, row(3, "STONE"));
        builder.advance(3);

        BuildSnapshot snapshot = builder.snapshotSoFar();
        assertThat(snapshot.size()).isEqualTo(3);

        BatchBuilder undo = new BatchBuilder(ground, snapshot.asRestorePlacements());
        undo.advance(undo.total());

        assertThat(ground.materialAt(new Spot(WORLD, 0, 64, 0))).isEqualTo("GRASS_BLOCK");
        assertThat(ground.materialAt(new Spot(WORLD, 2, 64, 0))).isEqualTo("GRASS_BLOCK");
    }

    @Test
    @DisplayName("a block that was already the right material is left alone and never recorded")
    void skipsWhatIsAlreadyRight() {
        MapGround ground = new MapGround().fillWith("STONE");
        BatchBuilder builder = new BatchBuilder(ground, row(5, "STONE"));

        builder.advance(5);

        assertThat(builder.snapshotSoFar().size()).isZero();
    }

    @Test
    @DisplayName("a placement the world refuses is not recorded — undoing it would invent a block")
    void refusedPlacementsAreNotRecorded() {
        Spot refusedSpot = new Spot(WORLD, 2, 64, 0);
        MapGround ground = new MapGround().fillWith("GRASS_BLOCK").refuse(refusedSpot);
        BatchBuilder builder = new BatchBuilder(ground, row(4, "STONE"));

        builder.advance(4);

        assertThat(builder.snapshotSoFar().placements())
                .extracting(BuildSnapshot.Placement::spot)
                .doesNotContain(refusedSpot);
        assertThat(builder.snapshotSoFar().size()).isEqualTo(3);
    }

    @Test
    @DisplayName("an unloaded position is skipped rather than loading the chunk to build into it")
    void skipsUnloadedGround() {
        Spot unloadedSpot = new Spot(WORLD, 1, 64, 0);
        MapGround ground = new MapGround().unload(unloadedSpot);
        BatchBuilder builder = new BatchBuilder(ground, row(3, "STONE"));

        builder.advance(3);

        assertThat(ground.materialAt(unloadedSpot)).isEqualTo("AIR");
        assertThat(builder.snapshotSoFar().size()).isEqualTo(2);
    }

    @Test
    @DisplayName("the snapshot grows with the build, so an interrupted build still undoes cleanly")
    void snapshotTracksPartialProgress() {
        MapGround ground = new MapGround().fillWith("DIRT");
        BatchBuilder builder = new BatchBuilder(ground, row(10, "STONE"));

        builder.advance(3);

        assertThat(builder.snapshotSoFar().size()).isEqualTo(3);
        assertThat(builder.placed()).isEqualTo(3);
        assertThat(builder.remaining()).isEqualTo(7);
    }
}
