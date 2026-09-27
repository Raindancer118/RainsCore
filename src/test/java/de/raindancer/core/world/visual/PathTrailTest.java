package de.raindancer.core.world.visual;

import de.raindancer.core.world.visual.PathTrail.Dot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** A dotted line from somebody towards where they are going — "particle GPS". */
class PathTrailTest {

    @Test
    @DisplayName("dots start a little ahead, are evenly spaced, and stop at the maximum length")
    void spacedAndCapped() {
        List<Dot> dots = PathTrail.toward(0, 64, 0, 100, 64, 0, 1.5, 1.0, 10);

        assertThat(dots.getFirst().x()).isCloseTo(1.5, within(1e-9));
        assertThat(dots.get(1).x() - dots.get(0).x()).isCloseTo(1.0, within(1e-9));
        assertThat(dots.getLast().x()).isLessThanOrEqualTo(10.0 + 1e-9);
        assertThat(dots).allSatisfy(dot -> {
            assertThat(dot.y()).isCloseTo(64, within(1e-9));
            assertThat(dot.z()).isCloseTo(0, within(1e-9));
        });
    }

    @Test
    @DisplayName("every dot lies on the straight line to the target, in 3D")
    void onTheLine() {
        for (Dot dot : PathTrail.toward(0, 60, 0, 30, 90, 40, 1, 2, 50)) {
            // Direction (30, 30, 40) normalised: every dot is a multiple of it.
            double t = dot.x() / 30.0;
            assertThat(dot.y() - 60).isCloseTo(30 * t, within(1e-9));
            assertThat(dot.z()).isCloseTo(40 * t, within(1e-9));
        }
    }

    @Test
    @DisplayName("a target closer than the maximum length is not overshot")
    void stopsAtTheTarget() {
        List<Dot> dots = PathTrail.toward(0, 64, 0, 5, 64, 0, 1, 1, 20);

        assertThat(dots).isNotEmpty();
        assertThat(dots.getLast().x()).isLessThan(5.0);
    }

    @Test
    @DisplayName("standing on the target, or nonsense spacing, draws nothing")
    void nothing() {
        assertThat(PathTrail.toward(3, 64, 3, 3, 64, 3, 1, 1, 10)).isEmpty();
        assertThat(PathTrail.toward(0, 64, 0, 10, 64, 0, 1, 0, 10)).isEmpty();
        assertThat(PathTrail.toward(0, 64, 0, 10, 64, 0, 1, 1, 0)).isEmpty();
    }
}
