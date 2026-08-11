package de.raindancer.core.world.teleport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where somebody lands, which is the arithmetic that decides whether a scattered arrival actually works.
 *
 * <h2>Why this is drawn ten thousand times rather than asserted once</h2>
 * Because the bug it is looking for is not a wrong answer, it is a wrongly distributed one — and no
 * single point can be wrong. Picked naively, with the radius drawn straight from the generator, half of
 * all arrivals land inside half the radius, which is a quarter of the ground: the middle is stripped
 * bare in a week and the outer ring is never touched. Every individual coordinate would look perfectly
 * reasonable.
 *
 * <p>So the test counts. Ten thousand points is enough that the inner and outer halves of the ring have
 * to come out close to their share of the area, and cheap enough to run on every build.
 */
class ScatterTest {

    /** The shape a first consumer (the farm worlds) shipped with, kept here as a sensible fixture. */
    private static final Scatter SENSIBLE = new Scatter(true, 250, 4000);

    @Nested
    @DisplayName("the range it will use")
    class Range {

        @Test
        @DisplayName("a sensible ring leaves the middle alone and does not reach absurdly far")
        void aSensibleRingIsWellFormed() {
            assertThat(SENSIBLE.isOn()).isTrue();
            assertThat(SENSIBLE.nearest())
                    .as("the middle is often where the portals and whatever an admin built are")
                    .isPositive();
            assertThat(SENSIBLE.furthest()).isGreaterThan(SENSIBLE.nearest());
        }

        @Test
        @DisplayName("two numbers the wrong way round are read as the ring they obviously mean")
        void swappedIsNotRefused() {
            // Refusing would mean a config typo silently switching arrivals off, which is the one
            // setting whose off position makes the whole feature pointless.
            Scatter scatter = new Scatter(true, 4000, 250);

            assertThat(scatter.nearest()).isEqualTo(250);
            assertThat(scatter.furthest()).isEqualTo(4000);
            assertThat(scatter.isOn()).isTrue();
        }

        @Test
        @DisplayName("the same number twice is still a ring, not a circle of zero width")
        void equalIsWidened() {
            Scatter scatter = new Scatter(true, 1000, 1000);

            assertThat(scatter.furthest()).isEqualTo(1000);
            assertThat(scatter.nearest()).isLessThan(1000);
            assertThat(scatter.isOn())
                    .as("nearest == furthest would be every arrival on one circle, which is a spawn "
                            + "point with extra steps")
                    .isTrue();
        }

        @Test
        @DisplayName("a negative or enormous number is clamped rather than thrown")
        void nonsenseIsClamped() {
            assertThat(new Scatter(true, -500, 2000).nearest()).isZero();
            assertThat(new Scatter(true, 0, 9_000_000).furthest())
                    .isEqualTo(Scatter.FURTHEST_ALLOWED);
            assertThat(new Scatter(true, 0, 1).furthest())
                    .as("a ring one block wide is not one")
                    .isEqualTo(Scatter.NEAREST_ALLOWED);
        }

        @Test
        @DisplayName("switched off, nothing is picked at all")
        void offIsOff() {
            assertThat(Scatter.NOWHERE.isOn()).isFalse();
            assertThat(new Scatter(false, 250, 4000).isOn()).isFalse();
            assertThat(new Scatter(false, 250, 4000).pick(new Random(1)))
                    .as("the caller reads this as 'the middle'")
                    .isEqualTo(new Scatter.Point(0, 0));
        }
    }

    @Nested
    @DisplayName("kept inside a world border")
    class Borders {

        @Test
        @DisplayName("a border smaller than the ring wins, with room to stand")
        void theBorderWins() {
            Scatter scatter = new Scatter(true, 250, 8000).within(2000);

            assertThat(scatter.furthest())
                    .as("landing against the border wall is landing somewhere a player cannot walk in "
                            + "three of four directions, which reads as a broken teleport")
                    .isEqualTo(2000 - Scatter.BORDER_MARGIN);
            assertThat(scatter.nearest()).isLessThan(scatter.furthest());
        }

        @Test
        @DisplayName("a border wider than the ring changes nothing")
        void aWideBorderIsIgnored() {
            Scatter scatter = new Scatter(true, 250, 4000);

            assertThat(scatter.within(50_000)).isEqualTo(scatter);
            assertThat(scatter.within(null)).isEqualTo(scatter);
            assertThat(scatter.within(0)).as("zero is how 'no border' arrives").isEqualTo(scatter);
        }

        @Test
        @DisplayName("a border with no room in it puts everybody at the middle instead")
        void aTinyBorderSwitchesScatteringOff() {
            // Pretending otherwise means arrivals against the wall — a teleport that fails for a reason
            // nothing on screen could explain.
            Scatter scatter = new Scatter(true, 250, 4000).within(Scatter.BORDER_MARGIN - 1);

            assertThat(scatter.isOn()).isFalse();
        }

        @Test
        @DisplayName("no arrival ever lands outside the border")
        void nothingEscapes() {
            Scatter scatter = new Scatter(true, 100, 9000).within(1500);
            Random random = new Random(7);

            for (int round = 0; round < 5_000; round++) {
                Scatter.Point point = scatter.pick(random);
                assertThat(point.distance()).isLessThanOrEqualTo(1500 - Scatter.BORDER_MARGIN);
            }
        }
    }

    @Nested
    @DisplayName("how the points are spread")
    class Spread {

        @Test
        @DisplayName("every point is inside the ring, both edges")
        void insideTheRing() {
            Scatter scatter = new Scatter(true, 500, 3000);
            Random random = new Random(42);

            for (int round = 0; round < 10_000; round++) {
                int distance = scatter.pick(random).distance();
                // A block of slack at each edge: the coordinates are rounded to whole blocks, and a
                // point on the exact inner circle can round one block inwards.
                assertThat(distance).isBetween(499, 3001);
            }
        }

        @Test
        @DisplayName("the outer half of the ring gets its share of the arrivals, not a fifth of them")
        void theSpreadIsByAreaAndNotByRadius() {
            // The whole reason this class exists. Split the ring at the radius that halves its *area*:
            // an even spread puts half the arrivals on each side. Drawing the radius straight from the
            // generator instead — which is what a first attempt does — puts about two thirds inside,
            // and every individual point still looks perfectly reasonable.
            int nearest = 0;
            int furthest = 4000;
            Scatter scatter = new Scatter(true, nearest, furthest);
            double halfway = Math.sqrt((double) furthest * furthest / 2);

            Random random = new Random(1234);
            int inside = 0;
            int rounds = 20_000;
            for (int round = 0; round < rounds; round++) {
                if (scatter.pick(random).distance() < halfway) {
                    inside++;
                }
            }

            double share = (double) inside / rounds;
            assertThat(share)
                    .as("half the area is inside %.0f blocks, so about half the arrivals should be — a "
                            + "radius drawn straight from the generator gives about 0.71", halfway)
                    .isBetween(0.47, 0.53);
        }

        @Test
        @DisplayName("the angles go all the way round")
        void everyDirectionIsUsed() {
            // A ring picked with an angle that never reaches 2π, or with the sine and cosine confused,
            // comes out as an arc or a diagonal line. Both would pass every other test here.
            Scatter scatter = new Scatter(true, 1000, 2000);
            Random random = new Random(99);
            boolean[] quadrants = new boolean[4];

            for (int round = 0; round < 2_000; round++) {
                Scatter.Point point = scatter.pick(random);
                int quadrant = (point.x() >= 0 ? 0 : 1) + (point.z() >= 0 ? 0 : 2);
                quadrants[quadrant] = true;
            }

            assertThat(quadrants).containsOnly(true);
        }

        @Test
        @DisplayName("two arrivals in the same second are not the same place")
        void twoArrivalsDiffer() {
            // Which is why the generator is not seeded from anything about the world. Seeded from a
            // world's own seed — the obvious thing to reach for — every player who arrived would land
            // on the same spot until the server restarted.
            Scatter scatter = new Scatter(true, 250, 4000);
            Random random = new Random();

            assertThat(scatter.pick(random)).isNotEqualTo(scatter.pick(random));
        }

        @Test
        @DisplayName("the same sequence gives the same points, so this test means something")
        void itIsAFunctionOfItsArguments() {
            Scatter scatter = new Scatter(true, 250, 4000);

            assertThat(scatter.pick(new Random(5))).isEqualTo(scatter.pick(new Random(5)));
        }
    }
}
