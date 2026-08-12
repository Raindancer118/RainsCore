package de.raindancer.core.world.geometry;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Distance and "is this even comparable" — see {@link Proximity}'s javadoc for why worlds are
 * checked before any coordinate math happens.
 */
class ProximityTest {

    private final World alpha = mock(World.class);
    private final World beta = mock(World.class);

    @Test
    void flatIgnoresHeightHorizontalDoesNot() {
        Location a = new Location(alpha, 0, 0, 0);
        Location b = new Location(alpha, 0, 100, 0);   // straight up, ten thousand blocks of fall

        assertThat(Proximity.flat(a, b)).isZero();
        assertThat(Proximity.horizontal(a, b)).isEqualTo(100.0);
    }

    @Test
    void flatAndHorizontalAgreeOnTheGround() {
        Location a = new Location(alpha, 0, 64, 0);
        Location b = new Location(alpha, 3, 64, 4);

        assertThat(Proximity.flat(a, b)).isEqualTo(5.0);
        assertThat(Proximity.horizontal(a, b)).isEqualTo(5.0);
    }

    @Test
    void withinIsTrueUnderTheLimitAndFalseOverIt() {
        Location a = new Location(alpha, 0, 64, 0);
        Location close = new Location(alpha, 3, 64, 0);
        Location far = new Location(alpha, 300, 64, 0);

        assertThat(Proximity.within(a, close, 5.0)).isTrue();
        assertThat(Proximity.within(a, far, 5.0)).isFalse();
    }

    @Test
    void differentWorldsAreNeverClose() {
        Location here = new Location(alpha, 0, 64, 0);
        Location thereSameCoordinates = new Location(beta, 0, 64, 0);

        assertThat(Proximity.sameWorld(here, thereSameCoordinates)).isFalse();
        assertThat(Proximity.horizontal(here, thereSameCoordinates)).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(Proximity.flat(here, thereSameCoordinates)).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(Proximity.within(here, thereSameCoordinates, 1_000_000.0)).isFalse();
    }

    @Test
    void nullWorldIsNeverTheSameWorld() {
        Location noWorld = new Location(null, 0, 64, 0);
        Location withWorld = new Location(alpha, 0, 64, 0);

        assertThat(Proximity.sameWorld(noWorld, withWorld)).isFalse();
    }
}
