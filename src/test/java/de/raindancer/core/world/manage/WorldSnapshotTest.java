package de.raindancer.core.world.manage;

import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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

    /**
     * Game rules as a server answers them, per world. {@code GameRule} itself cannot exist without a
     * running server, so the typed side is {@link PaperGameRules}' and is checked on a real one.
     */
    static final class FakeRules implements GameRuleAccess {
        final Map<World, Map<String, String>> worlds = new IdentityHashMap<>();
        final Map<String, Class<?>> known = new LinkedHashMap<>(Map.of(
                "keep_inventory", Boolean.class, "mob_griefing", Boolean.class, "respawn_radius", Integer.class));

        @Override
        public Map<String, String> read(World world) {
            return worlds.getOrDefault(world, Map.of());
        }

        @Override
        public Optional<Class<?>> typeOf(String name) {
            return Optional.ofNullable(known.get(name));
        }

        @Override
        public void write(World world, String name, Object value) {
            worlds.computeIfAbsent(world, ignored -> new HashMap<>()).put(name, String.valueOf(value));
        }
    }

    private final FakeRules rules = new FakeRules();

    @BeforeEach
    void useTheFake() {
        WorldSnapshot.useRules(rules);
    }

    @AfterEach
    void putTheServersBack() {
        WorldSnapshot.useRules(null);
    }

    private World configured() {
        World world = mock(World.class);
        // Minecraft 26.3 names: game rules are registry entries now, and the old camelCase ones are gone.
        rules.worlds.put(world, Map.of("keep_inventory", "true", "mob_griefing", "false", "respawn_radius", "3"));
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

        assertThat(rules.read(fresh)).containsEntry("keep_inventory", "true")
                .containsEntry("mob_griefing", "false")
                .containsEntry("respawn_radius", "3");
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

    @Test
    @DisplayName("a rule this server does not know, or a value that is not of its type, is skipped — the rest still apply")
    void unknownRulesAreSkipped() {
        WorldSnapshot snapshot = new WorldSnapshot(Map.of(
                "doDaylightCycle", "false", "respawn_radius", "lots", "mob_griefing", "maybe", "keep_inventory", "TRUE"),
                null, 0, 0, WorldSnapshot.VANILLA_BORDER, 0, 0, 0, 0f, false);
        World fresh = fresh();

        snapshot.applyTo(fresh, false);

        assertThat(rules.read(fresh)).containsExactly(Map.entry("keep_inventory", "true"));
    }

    @Test
    @DisplayName("text becomes the rule's own type: a switch or a number, nothing else")
    void parsing() {
        assertThat(GameRuleAccess.parse(Boolean.class, "true")).contains(true);
        assertThat(GameRuleAccess.parse(Boolean.class, " False ")).contains(false);
        assertThat(GameRuleAccess.parse(Boolean.class, "yes")).isEmpty();
        assertThat(GameRuleAccess.parse(Integer.class, "-5")).contains(-5);
        assertThat(GameRuleAccess.parse(Integer.class, "5.5")).isEmpty();
        assertThat(GameRuleAccess.parse(String.class, "x")).isEmpty();
        assertThat(GameRuleAccess.parse(Integer.class, null)).isEmpty();
    }
}
