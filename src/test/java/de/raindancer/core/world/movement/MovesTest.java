package de.raindancer.core.world.movement;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MovesTest {

    private static World world(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        return world;
    }

    private final World overworld = world("world");
    private final World nether = world("world_nether");

    private PlayerMoveEvent move(Location from, Location to) {
        return new PlayerMoveEvent(mock(Player.class), from, to);
    }

    @Test
    @DisplayName("inside one block, head turns included, is not a change of block")
    void withinOneBlock() {
        Location from = new Location(overworld, 10.2, 64, 5.9, 0, 0);
        Location to = new Location(overworld, 10.8, 64.4, 5.1, 90, 30);

        assertThat(Moves.sameBlock(from, to)).isTrue();
        assertThat(Moves.changedBlock(move(from, to))).isFalse();
        assertThat(Moves.changedPosition(move(from, to))).isTrue();
    }

    @Test
    @DisplayName("negative coordinates round down, so -0.5 and 0.5 are different blocks")
    void negativeCoordinates() {
        assertThat(Moves.sameBlock(new Location(overworld, -0.5, 64, 0), new Location(overworld, 0.5, 64, 0)))
                .isFalse();
    }

    @Test
    @DisplayName("the same coordinates in another world are somewhere else")
    void otherWorld() {
        Location here = new Location(overworld, 1, 64, 1);
        Location there = new Location(nether, 1, 64, 1);

        assertThat(Moves.sameBlock(here, there)).isFalse();
        assertThat(Moves.changedWorld(move(here, there))).isTrue();
        assertThat(Moves.horizontalDistance(here, there)).isInfinite();
    }

    @Test
    @DisplayName("a turn of the head is not a move at all")
    void headOnly() {
        Location from = new Location(overworld, 1, 64, 1, 0, 0);
        Location to = new Location(overworld, 1, 64, 1, 180, -40);

        assertThat(Moves.changedPosition(move(from, to))).isFalse();
    }

    @Test
    @DisplayName("held in place, a player keeps their block but may still look around")
    void holdInPlace() {
        Location from = new Location(overworld, 1.5, 64, 1.5, 0, 0);
        PlayerMoveEvent stepping = move(from, new Location(overworld, 2.5, 64, 1.5, 90, 10));

        assertThat(Moves.holdInPlace(stepping)).isTrue();
        assertThat(Moves.sameBlock(stepping.getTo(), from)).isTrue();
        assertThat(stepping.getTo().getYaw()).isEqualTo(90);
        assertThat(stepping.isCancelled()).as("cancelling snaps the head back").isFalse();

        PlayerMoveEvent looking = move(from, new Location(overworld, 1.6, 64, 1.5, 45, 0));
        assertThat(Moves.holdInPlace(looking)).isFalse();
    }

    @Test
    @DisplayName("nulls are answered, not thrown")
    void nulls() {
        assertThat(Moves.sameBlock(null, null)).isTrue();
        assertThat(Moves.sameBlock(null, new Location(overworld, 0, 0, 0))).isFalse();
        assertThat(Moves.changedBlock(null)).isFalse();
        assertThat(Moves.horizontalDistance(new Location(overworld, 0, 0, 0), new Location(overworld, 3, 9, 4)))
                .isEqualTo(5.0);
    }
}
