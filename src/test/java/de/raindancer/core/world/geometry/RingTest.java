package de.raindancer.core.world.geometry;

import de.raindancer.core.world.geometry.Ring.Spot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Everybody evenly around one circle, the circle as large as the roster needs — first written for
 * Manhunt ("everyone should be spawned in a circle, depending on the count of people participating").
 */
class RingTest {

    private static double gap(Spot a, Spot b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    @Test
    @DisplayName("one spot per participant, every one the same distance from the centre")
    void everybodyOnOneCircle() {
        List<Spot> spots = Ring.around(100, -50, 8, 4, 4);

        assertThat(spots).hasSize(8);
        double radius = Math.hypot(spots.getFirst().x() - 100, spots.getFirst().z() + 50);
        for (Spot spot : spots) {
            assertThat(Math.hypot(spot.x() - 100, spot.z() + 50)).isCloseTo(radius, within(1e-9));
        }
    }

    @Test
    @DisplayName("neighbours stand exactly the spacing apart")
    void exactSpacing() {
        List<Spot> spots = Ring.around(0, 0, 10, 4, 1);

        assertThat(gap(spots.get(0), spots.get(1))).isCloseTo(4, within(1e-9));
    }

    @Test
    @DisplayName("the circle grows with the roster, so neighbours keep the same gap")
    void circleGrowsWithTheRoster() {
        List<Spot> few = Ring.around(0, 0, 10, 4, 1);
        List<Spot> many = Ring.around(0, 0, 40, 4, 1);

        assertThat(gap(few.get(0), few.get(1))).isCloseTo(gap(many.get(0), many.get(1)), within(0.05));
        assertThat(Math.hypot(many.getFirst().x(), many.getFirst().z()))
                .isGreaterThan(Math.hypot(few.getFirst().x(), few.getFirst().z()));
    }

    @Test
    @DisplayName("a small roster is never squeezed closer to the centre than the minimum radius")
    void smallRosterKeepsTheMinimumRadius() {
        for (Spot spot : Ring.around(0, 0, 2, 4, 6)) {
            assertThat(Math.hypot(spot.x(), spot.z())).isCloseTo(6, within(1e-9));
        }
    }

    @Test
    @DisplayName("one person stands on the minimum radius too, not in the middle")
    void onePerson() {
        List<Spot> one = Ring.around(0, 0, 1, 4, 5);

        assertThat(one).hasSize(1);
        assertThat(Math.hypot(one.getFirst().x(), one.getFirst().z())).isCloseTo(5, within(1e-9));
    }

    @Test
    @DisplayName("everybody starts facing the middle")
    void everybodyFacesTheCentre() {
        for (Spot spot : Ring.around(0, 0, 12, 4, 4)) {
            double lookX = -Math.sin(Math.toRadians(spot.yaw()));
            double lookZ = Math.cos(Math.toRadians(spot.yaw()));
            double length = Math.hypot(spot.x(), spot.z());
            assertThat(lookX).isCloseTo(-spot.x() / length, within(1e-6));
            assertThat(lookZ).isCloseTo(-spot.z() / length, within(1e-6));
        }
    }

    @Test
    @DisplayName("nobody at all is no spots, not a division by zero")
    void emptyRosterIsNoSpots() {
        assertThat(Ring.around(0, 0, 0, 4, 4)).isEmpty();
    }
}
