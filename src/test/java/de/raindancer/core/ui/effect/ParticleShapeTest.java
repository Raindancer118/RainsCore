package de.raindancer.core.ui.effect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Where a shape puts its particles, relative to the wearer's feet. Pure maths, so pinned here. */
class ParticleShapeTest {

    @Test
    @DisplayName("an aura circles the body, at body height, and moves round from tick to tick")
    void aura() {
        List<double[]> now = ParticleShape.AURA.offsets(0, 0);
        List<double[]> later = ParticleShape.AURA.offsets(1, 0);

        assertThat(now).isNotEmpty();
        for (double[] point : now) {
            assertThat(Math.hypot(point[0], point[2])).isCloseTo(ParticleShape.AURA_RADIUS, within(1e-9));
            assertThat(point[1]).isBetween(0.1, 2.0);
        }
        assertThat(later.getFirst()[0]).isNotCloseTo(now.getFirst()[0], within(1e-6));
    }

    @Test
    @DisplayName("a halo is a ring above the head")
    void halo() {
        for (double[] point : ParticleShape.HALO.offsets(7, 90)) {
            assertThat(point[1]).isGreaterThan(2.0);
            assertThat(Math.hypot(point[0], point[2])).isLessThan(0.5);
        }
    }

    @Test
    @DisplayName("a trail is behind you: facing south it is to the north of your feet")
    void trailIsBehind() {
        // Yaw 0 faces south (+z), so behind is -z.
        double[] point = ParticleShape.TRAIL.offsets(0, 0).getFirst();
        assertThat(point[2]).isNegative();
        assertThat(point[0]).isCloseTo(0, within(1e-9));
        assertThat(point[1]).isLessThan(0.5);

        // Yaw 90 faces west (-x), so behind is +x.
        double[] west = ParticleShape.TRAIL.offsets(0, 90).getFirst();
        assertThat(west[0]).isPositive();
    }

    @Test
    @DisplayName("a shape is found by the word a player types, in any case")
    void byName() {
        assertThat(ParticleShape.of("halo")).contains(ParticleShape.HALO);
        assertThat(ParticleShape.of("TRAIL")).contains(ParticleShape.TRAIL);
        assertThat(ParticleShape.of("cube")).isEmpty();
        assertThat(ParticleShape.of(null)).isEmpty();
    }

    @Test
    @DisplayName("ambient is scattered over the body like a potion effect, and differs from tick to tick")
    void ambient() {
        for (long tick = 0; tick < 50; tick++) {
            for (double[] point : ParticleShape.AMBIENT.offsets(tick, 0)) {
                assertThat(Math.abs(point[0])).isLessThanOrEqualTo(0.45);
                assertThat(Math.abs(point[2])).isLessThanOrEqualTo(0.45);
                assertThat(point[1]).isBetween(0.1, 1.9);
            }
        }
        assertThat(ParticleShape.AMBIENT.offsets(1, 0).getFirst()[0])
                .isNotCloseTo(ParticleShape.AMBIENT.offsets(2, 0).getFirst()[0], within(1e-9));
    }

    @Test
    @DisplayName("a spiral climbs the body and starts again at the feet")
    void spiral() {
        double low = ParticleShape.SPIRAL.offsets(0, 0).getFirst()[1];
        double high = ParticleShape.SPIRAL.offsets(19, 0).getFirst()[1];
        assertThat(high).isGreaterThan(low);
        assertThat(ParticleShape.SPIRAL.offsets(20, 0).getFirst()[1]).isCloseTo(low, within(1e-9));
    }
}
