package de.raindancer.core.world.visual;

import de.raindancer.core.world.visual.Navigator.Progress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Where somebody navigating to a point stands, as far as the navigation cares. */
class NavigatorTest {

    @Test
    @DisplayName("on the way: the distance, in blocks")
    void onTheWay() {
        Progress progress = Navigator.progress("world", 0, 64, 0, "world", 30, 64, 40);

        assertThat(progress.kind()).isEqualTo(Progress.Kind.ON_THE_WAY);
        assertThat(progress.distance()).isCloseTo(50, within(1e-9));
    }

    @Test
    @DisplayName("within a few blocks is arrived")
    void arrived() {
        assertThat(Navigator.progress("world", 0, 64, 0, "world", 2, 65, 1).kind())
                .isEqualTo(Progress.Kind.ARRIVED);
    }

    @Test
    @DisplayName("height counts too — standing right above it in a cave is not there")
    void heightCounts() {
        assertThat(Navigator.progress("world", 0, 100, 0, "world", 0, 20, 0).kind())
                .isEqualTo(Progress.Kind.ON_THE_WAY);
    }

    @Test
    @DisplayName("another world has no distance")
    void otherWorld() {
        Progress progress = Navigator.progress("world_nether", 0, 64, 0, "world", 0, 64, 0);

        assertThat(progress.kind()).isEqualTo(Progress.Kind.OTHER_WORLD);
    }
}
