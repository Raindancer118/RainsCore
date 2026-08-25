package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What was there before a build touched it. */
class BuildSnapshotTest {

    @Test
    @DisplayName("an empty snapshot restores nothing")
    void emptyIsEmpty() {
        assertThat(BuildSnapshot.empty().size()).isZero();
        assertThat(BuildSnapshot.empty().asRestorePlacements()).isEmpty();
    }

    @Test
    @DisplayName("restoring runs in reverse, so a block put back last is the one that was covered first")
    void restoresInReverseOrder() {
        Spot first = new Spot("world", 0, 64, 0);
        Spot second = new Spot("world", 1, 64, 0);
        BuildSnapshot snapshot = new BuildSnapshot(List.of(
                new BuildSnapshot.Placement(first, "DIRT"),
                new BuildSnapshot.Placement(second, "STONE")));

        List<BatchBuilder.Placement> restore = snapshot.asRestorePlacements();

        assertThat(restore).extracting(BatchBuilder.Placement::spot).containsExactly(second, first);
        assertThat(restore).extracting(BatchBuilder.Placement::material).containsExactly("STONE", "DIRT");
    }

    @Test
    @DisplayName("a snapshot cannot be changed from under whoever holds it")
    void isImmutable() {
        List<BuildSnapshot.Placement> mutable = new java.util.ArrayList<>();
        mutable.add(new BuildSnapshot.Placement(new Spot("world", 0, 64, 0), "DIRT"));
        BuildSnapshot snapshot = new BuildSnapshot(mutable);

        mutable.clear();

        assertThat(snapshot.size()).isEqualTo(1);
    }
}
