package de.raindancer.core.world.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resetting a speedrun world: same pattern as {@code FarmWorlds.regenerateOne} (see its javadoc for
 * why the order matters), copied here as its own small self-contained method rather than reused,
 * because none of {@code FarmWorlds}'s set/state bookkeeping applies to a single speedrun map.
 */
class SpeedrunResetTest {

    private final SpeedrunReset reset = new SpeedrunReset();

    @TempDir
    Path serverDirectory;

    private Path worldFolder;
    private World world;

    @BeforeEach
    void setUp() throws IOException {
        // A real folder with a nested file, so deletion is genuinely exercised rather than assumed.
        worldFolder = serverDirectory.resolve("speedrun_map");
        Files.createDirectories(worldFolder.resolve("region"));
        Files.writeString(worldFolder.resolve("level.dat"), "not real nbt, just a marker file");
        Files.writeString(worldFolder.resolve("region").resolve("r.0.0.mca"), "region data");

        world = mock(World.class);
        when(world.getName()).thenReturn("speedrun_map");
        when(world.getWorldFolder()).thenReturn(worldFolder.toFile());
    }

    @Test
    @DisplayName("reads getWorldFolder() from the still-loaded World before unloading")
    void readsFolderFromTheWorldItself() {
        // The whole point of the FarmWorlds fix this mirrors: resolving the folder from the world
        // container by name alone can point at the wrong place on Paper 26 (dimensions/<ns>/<name>
        // for non-primary worlds). Reading it from the loaded World sidesteps that entirely.
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class,
                     (mockCreator, context) -> when(mockCreator.createWorld())
                             .thenReturn(mock(World.class)))) {
            stubServerBasics(bukkit);

            boolean ok = reset.regenerate(world, SpeedrunSeed.random(), Set.of());

            assertThat(ok).isTrue();
            // getWorldFolder() must have been asked, and nothing else guessed the path.
            verify(world).getWorldFolder();
            assertThat(worldFolder).doesNotExist();
        }
    }

    @Nested
    @DisplayName("seed policy")
    class SeedPolicy {

        @Test
        @DisplayName("a fixed seed is applied to the WorldCreator")
        void fixedSeedApplied() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class,
                         (mockCreator, context) -> {
                             when(mockCreator.seed(anyLong())).thenReturn(mockCreator);
                             when(mockCreator.createWorld()).thenReturn(mock(World.class));
                         })) {
                stubServerBasics(bukkit);

                boolean ok = reset.regenerate(world, SpeedrunSeed.fixed(42L), Set.of());

                assertThat(ok).isTrue();
                assertThat(creators.constructed()).hasSize(1);
                verify(creators.constructed().getFirst()).seed(eq(42L));
            }
        }

        @Test
        @DisplayName("a random seed is left untouched")
        void randomSeedNotApplied() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class,
                         (mockCreator, context) -> when(mockCreator.createWorld())
                                 .thenReturn(mock(World.class)))) {
                stubServerBasics(bukkit);

                boolean ok = reset.regenerate(world, SpeedrunSeed.random(), Set.of());

                assertThat(ok).isTrue();
                assertThat(creators.constructed()).hasSize(1);
                verify(creators.constructed().getFirst(), never()).seed(anyLong());
            }
        }
    }

    @Test
    @DisplayName("a world that would not unload is left alone and the folder survives")
    void unloadFailureLeavesFolderAlone() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            World mainWorld = mock(World.class);
            Location spawn = mock(Location.class);
            when(mainWorld.getSpawnLocation()).thenReturn(spawn);
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(mainWorld));
            bukkit.when(() -> Bukkit.unloadWorld(world, false)).thenReturn(false);

            boolean ok = reset.regenerate(world, SpeedrunSeed.random(), Set.of());

            assertThat(ok).isFalse();
            assertThat(worldFolder).exists();
        }
    }

    private void stubServerBasics(MockedStatic<Bukkit> bukkit) {
        World mainWorld = mock(World.class);
        Location spawn = mock(Location.class);
        when(mainWorld.getSpawnLocation()).thenReturn(spawn);
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of(mainWorld));
        bukkit.when(() -> Bukkit.unloadWorld(world, false)).thenReturn(true);
    }
}
