package de.raindancer.core.world.manage;

import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an owner set up on a world, carried across a regeneration. The complaint every world manager
 * hears first: "I reset the farm world and now it rains, mobs grief again and the border is gone".
 */
class WorldSnapshotTest {

    private static World configured() {
        World world = mock(World.class);
        when(world.getGameRules()).thenReturn(new String[]{"keepInventory", "doMobGriefing"});
        when(world.getGameRuleValue("keepInventory")).thenReturn("true");
        when(world.getGameRuleValue("doMobGriefing")).thenReturn("false");
        when(world.getDifficulty()).thenReturn(Difficulty.HARD);
        WorldBorder border = mock(WorldBorder.class);
        Location center = mock(Location.class);
        when(center.getX()).thenReturn(100.0);
        when(center.getZ()).thenReturn(-200.0);
        when(border.getCenter()).thenReturn(center);
        when(border.getSize()).thenReturn(5000.0);
        when(world.getWorldBorder()).thenReturn(border);
        Location spawn = mock(Location.class);
        when(spawn.getX()).thenReturn(10.5);
        when(spawn.getY()).thenReturn(70.0);
        when(spawn.getZ()).thenReturn(-4.5);
        when(spawn.getYaw()).thenReturn(90f);
        when(world.getSpawnLocation()).thenReturn(spawn);
        return world;
    }

    private static World fresh() {
        World world = mock(World.class);
        when(world.getWorldBorder()).thenReturn(mock(WorldBorder.class));
        return world;
    }

    @Test
    @DisplayName("game rules, difficulty and the border come back on the new world")
    void rulesDifficultyBorder() {
        WorldSnapshot snapshot = WorldSnapshot.of(configured());
        World fresh = fresh();

        snapshot.applyTo(fresh, false);

        verify(fresh).setGameRuleValue("keepInventory", "true");
        verify(fresh).setGameRuleValue("doMobGriefing", "false");
        verify(fresh).setDifficulty(Difficulty.HARD);
        verify(fresh.getWorldBorder()).setCenter(100.0, -200.0);
        verify(fresh.getWorldBorder()).setSize(5000.0);
    }

    @Test
    @DisplayName("the spawn point only when the map is the same map — on another seed it may be mid-ocean")
    void spawnOnlyOnTheSameMap() {
        WorldSnapshot snapshot = WorldSnapshot.of(configured());

        World sameMap = fresh();
        snapshot.applyTo(sameMap, true);
        verify(sameMap).setSpawnLocation(any(Location.class));

        World otherMap = fresh();
        snapshot.applyTo(otherMap, false);
        verify(otherMap, never()).setSpawnLocation(any(Location.class));
    }

    @Test
    @DisplayName("a vanilla-sized border is not written, so the new world keeps its own default")
    void defaultBorderIsLeftAlone() {
        World world = configured();
        when(world.getWorldBorder().getSize()).thenReturn(WorldSnapshot.VANILLA_BORDER);
        World fresh = fresh();

        WorldSnapshot.of(world).applyTo(fresh, false);

        verify(fresh.getWorldBorder(), never()).setSize(anyDouble());
    }

    @Test
    @DisplayName("a world that answers nothing is an empty snapshot, not an exception")
    void nothingToCarry() {
        WorldSnapshot snapshot = WorldSnapshot.of(mock(World.class));

        assertThat(snapshot.gameRules()).isEmpty();
        snapshot.applyTo(fresh(), true);
        assertThat(WorldSnapshot.of(null).gameRules()).isEmpty();
    }
}
