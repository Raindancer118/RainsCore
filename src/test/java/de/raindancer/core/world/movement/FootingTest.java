package de.raindancer.core.world.movement;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whether somebody stands on something, as the server sees it. Paper deprecates
 * {@code Player#isOnGround} because the client says it, so anything that acts on it can be lied to.
 */
class FootingTest {

    private static Entity standingAt(double feetY, World world) {
        Entity entity = mock(Entity.class);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getBoundingBox()).thenReturn(new BoundingBox(10.2, feetY, 4.2, 10.8, feetY + 1.8, 4.8));
        return entity;
    }

    @Test
    @DisplayName("asks the world about a thin slice right under the feet, as wide as the body")
    void asksUnderTheFeet() {
        World world = mock(World.class);
        when(world.hasCollisionsIn(any())).thenReturn(true);

        assertThat(Footing.grounded(standingAt(64.0, world))).isTrue();

        ArgumentCaptor<BoundingBox> slice = ArgumentCaptor.forClass(BoundingBox.class);
        verify(world).hasCollisionsIn(slice.capture());
        assertThat(slice.getValue().getMaxY()).isCloseTo(64.0, within(1e-9));
        assertThat(slice.getValue().getMinY()).isLessThan(64.0).isGreaterThan(63.9);
        assertThat(slice.getValue().getMinX()).isEqualTo(10.2);
        assertThat(slice.getValue().getMaxZ()).isEqualTo(4.8);
    }

    @Test
    @DisplayName("nothing under the feet is not grounded, whatever the client claims")
    void airborne() {
        World world = mock(World.class);
        when(world.hasCollisionsIn(any())).thenReturn(false);

        assertThat(Footing.grounded(standingAt(80.5, world))).isFalse();
    }

    @Test
    @DisplayName("no entity, or one in no world, stands on nothing")
    void nothing() {
        assertThat(Footing.grounded(null)).isFalse();
        assertThat(Footing.grounded(mock(Entity.class))).isFalse();
    }
}
