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

    @Test
    @DisplayName("denser is more points along the shape: a dense halo is an actual ring, evenly spaced")
    void denseHaloIsARing() {
        List<double[]> ring = ParticleShape.HALO.offsets(3, 0, 4);

        assertThat(ring).hasSizeGreaterThanOrEqualTo(16);
        double step = 2 * Math.PI / ring.size();
        for (int i = 0; i < ring.size(); i++) {
            double[] point = ring.get(i);
            double[] next = ring.get((i + 1) % ring.size());
            assertThat(Math.hypot(point[0], point[2])).isCloseTo(0.35, within(1e-9));
            double gap = Math.hypot(next[0] - point[0], next[2] - point[2]);
            assertThat(gap).isCloseTo(2 * 0.35 * Math.sin(step / 2), within(1e-9));
        }
    }

    @Test
    @DisplayName("every shape gets more distinct points as it gets denser, never more on the same spot")
    void denserIsMoreSpots() {
        for (ParticleShape shape : ParticleShape.values()) {
            List<double[]> light = shape.offsets(5, 30, 1);
            List<double[]> dense = shape.offsets(5, 30, 4);

            assertThat(dense.size()).as(shape.name()).isGreaterThan(light.size());
            long distinct = dense.stream()
                    .map(p -> Math.round(p[0] * 1000) + "," + Math.round(p[1] * 1000) + "," + Math.round(p[2] * 1000))
                    .distinct().count();
            assertThat(distinct).as(shape.name()).isEqualTo(dense.size());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ParticleShape.class,
            names = {"WINGS", "BAT_WINGS", "BUTTERFLY_WINGS", "HUMMINGBIRD_WINGS"})
    @DisplayName("every kind of wings is on your back: behind you, spread to both sides alike, waist to above the head")
    void wings(ParticleShape kind) {
        assertThat(kind.isWings()).isTrue();
        // Facing south (+z): behind is north (-z).
        List<double[]> wings = kind.offsets(0, 0, 2);

        assertThat(wings).allSatisfy(p -> {
            assertThat(p[2]).isNegative();
            assertThat(p[1]).isBetween(0.7, 2.6);
        });
        assertThat(wings.stream().mapToDouble(p -> p[0]).min().orElseThrow()).isLessThan(-0.8);
        assertThat(wings.stream().mapToDouble(p -> p[0]).max().orElseThrow()).isGreaterThan(0.8);
        for (double[] p : wings) {
            assertThat(wings).anySatisfy(q -> {
                assertThat(q[0]).isCloseTo(-p[0], within(1e-9));
                assertThat(q[1]).isCloseTo(p[1], within(1e-9));
                assertThat(q[2]).isCloseTo(p[2], within(1e-9));
            });
        }

        // Facing west (yaw 90, -x): the wings are to the east of the feet.
        assertThat(kind.offsets(0, 90, 2)).allSatisfy(p -> assertThat(p[0]).isPositive());
    }

    @Test
    @DisplayName("the kinds of wings are different shapes, and nothing else counts as wings")
    void kindsDiffer() {
        List<double[]> angel = ParticleShape.WINGS.offsets(0, 0, 2);
        List<double[]> bat = ParticleShape.BAT_WINGS.offsets(0, 0, 2);
        List<double[]> butterfly = ParticleShape.BUTTERFLY_WINGS.offsets(0, 0, 2);
        assertThat(highest(angel)).isNotCloseTo(highest(bat), within(0.05));
        assertThat(lowest(butterfly)).isNotCloseTo(lowest(angel), within(0.05));
        assertThat(ParticleShape.HALO.isWings()).isFalse();
        assertThat(ParticleShape.wings()).containsExactly(ParticleShape.WINGS, ParticleShape.BAT_WINGS,
                ParticleShape.BUTTERFLY_WINGS, ParticleShape.HUMMINGBIRD_WINGS);
    }

    @Test
    @DisplayName("hummingbird wings beat far faster than angel wings: many turns in one second, against two at most")
    void hummingbirdIsFast() {
        assertThat(turnsInASecond(ParticleShape.HUMMINGBIRD_WINGS)).isGreaterThanOrEqualTo(8);
        assertThat(turnsInASecond(ParticleShape.WINGS)).isLessThanOrEqualTo(2);
    }

    /** How often the wing tip changes direction over twenty ticks. */
    private static int turnsInASecond(ParticleShape kind) {
        java.util.Comparator<double[]> outward = java.util.Comparator.comparingDouble(p -> Math.abs(p[0]));
        double[] tips = new double[21];
        for (int tick = 0; tick <= 20; tick++) {
            tips[tick] = kind.offsets(tick, 0, 1).stream().max(outward).orElseThrow()[2];
        }
        int turns = 0;
        for (int tick = 1; tick < 20; tick++) {
            if (Math.signum(tips[tick] - tips[tick - 1]) != Math.signum(tips[tick + 1] - tips[tick])) {
                turns++;
            }
        }
        return turns;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ParticleShape.class,
            names = {"WINGS", "BAT_WINGS", "BUTTERFLY_WINGS", "HUMMINGBIRD_WINGS"})
    @DisplayName("wings are filled, not only outlined: the middle of a wing has particles in it")
    void wingsAreFilled(ParticleShape kind) {
        assertThat(middle(kind.offsets(0, 0, 4))).as(kind.name()).isPositive();
        assertThat(kind.offsets(0, 0, 4).size()).as(kind.name())
                .isGreaterThan(kind.offsets(0, 0, 1).size() * 2);
    }

    @Test
    @DisplayName("bat wings show their finger bones; ultra-dense angel wings their quills, on top of the fill")
    void innerLines() {
        assertThat(ParticleShape.WINGS.offsets(0, 0, ParticleShape.ULTRA).size())
                .isGreaterThan(ParticleShape.WINGS.offsets(0, 0, ParticleShape.ULTRA - 1).size() * 11 / 10);
    }

    /** Points of the right wing near its own centre, seen flat from behind. */
    private static long middle(List<double[]> points) {
        List<double[]> right = points.stream().filter(p -> p[0] < 0).toList();
        double cx = right.stream().mapToDouble(p -> p[0]).average().orElseThrow();
        double cy = right.stream().mapToDouble(p -> p[1]).average().orElseThrow();
        return right.stream().filter(p -> Math.abs(p[0] - cx) < 0.08 && Math.abs(p[1] - cy) < 0.08).count();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ParticleShape.class,
            names = {"WINGS", "BAT_WINGS", "BUTTERFLY_WINGS", "HUMMINGBIRD_WINGS"})
    @DisplayName("at Normal the points of a wing are close enough to read as one surface, and never two on one spot")
    void wingSpacing(ParticleShape kind) {
        assertThat(ParticleShape.wingStep(2)).isLessThanOrEqualTo(0.12);
        assertThat(ParticleShape.wingStep(ParticleShape.ULTRA)).isLessThan(ParticleShape.wingStep(6));
        List<double[]> points = kind.offsets(0, 0, 2);
        double tooClose = ParticleShape.wingStep(2) * 0.35;
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                double[] a = points.get(i);
                double[] b = points.get(j);
                assertThat(Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2)))
                        .as("%s points %d and %d", kind, i, j).isGreaterThan(tooClose);
            }
        }
    }

    private static double highest(List<double[]> points) {
        return points.stream().mapToDouble(p -> p[1]).max().orElseThrow();
    }

    private static double lowest(List<double[]> points) {
        return points.stream().mapToDouble(p -> p[1]).min().orElseThrow();
    }

    @Test
    @DisplayName("wings flap: the same wing is drawn somewhere else a few ticks later")
    void wingsFlap() {
        // The tip, which moves furthest.
        java.util.Comparator<double[]> outward = java.util.Comparator.comparingDouble(p -> Math.abs(p[0]));
        double[] now = ParticleShape.WINGS.offsets(0, 0, 1).stream().max(outward).orElseThrow();
        double[] later = ParticleShape.WINGS.offsets(6, 0, 1).stream().max(outward).orElseThrow();

        assertThat(Math.hypot(now[0] - later[0], now[2] - later[2])).isGreaterThan(0.05);
    }
}
