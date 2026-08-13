package de.raindancer.core.world.manage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wiping a world and making it again, generically — same shape as {@code SpeedrunResetTest}, minus a
 * seed policy: {@link WorldRegenerator} never sets one, on purpose, so there is nothing here to choose
 * between fixed and random.
 */
class WorldRegeneratorTest {

    private final WorldRegenerator regenerator = new WorldRegenerator();

    @TempDir
    Path serverDirectory;

    private Path worldFolder;
    private World world;

    @BeforeEach
    void setUp() throws IOException {
        worldFolder = serverDirectory.resolve("build");
        Files.createDirectories(worldFolder.resolve("region"));
        Files.writeString(worldFolder.resolve("level.dat"), "not real nbt, just a marker file");
        Files.writeString(worldFolder.resolve("region").resolve("r.0.0.mca"), "region data");

        world = mock(World.class);
        when(world.getName()).thenReturn("build");
        when(world.getWorldFolder()).thenReturn(worldFolder.toFile());
        when(world.getPlayers()).thenReturn(List.of());
    }

    @Test
    @DisplayName("reads getWorldFolder() from the still-loaded World before unloading")
    void readsFolderFromTheWorldItself() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class,
                     (mockCreator, context) -> when(mockCreator.createWorld())
                             .thenReturn(mock(World.class)))) {
            stubServerBasics(bukkit);

            boolean ok = regenerator.regenerate(world);

            assertThat(ok).isTrue();
            verify(world).getWorldFolder();
            assertThat(worldFolder).doesNotExist();
        }
    }

    @Test
    @DisplayName("never sets a seed on the new WorldCreator")
    void neverSetsASeed() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class,
                     (mockCreator, context) -> when(mockCreator.createWorld())
                             .thenReturn(mock(World.class)))) {
            stubServerBasics(bukkit);

            boolean ok = regenerator.regenerate(world);

            assertThat(ok).isTrue();
            assertThat(creators.constructed()).hasSize(1);
            verify(creators.constructed().getFirst(), never()).seed(anyLong());
        }
    }

    @Test
    @DisplayName("moves everybody standing in the world out before it is unloaded")
    void evacuatesOccupants() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class,
                     (mockCreator, context) -> when(mockCreator.createWorld())
                             .thenReturn(mock(World.class)))) {
            Player occupant = mock(Player.class);
            when(world.getPlayers()).thenReturn(List.of(occupant));
            stubServerBasics(bukkit);

            boolean ok = regenerator.regenerate(world);

            assertThat(ok).isTrue();
            verify(occupant).teleportAsync(org.mockito.ArgumentMatchers.any(Location.class));
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

            boolean ok = regenerator.regenerate(world);

            assertThat(ok).isFalse();
            assertThat(worldFolder).exists();
        }
    }

    @Test
    @DisplayName("a null world is refused rather than throwing")
    void nullWorldRefused() {
        assertThat(regenerator.regenerate(null)).isFalse();
    }

    private void stubServerBasics(MockedStatic<Bukkit> bukkit) {
        World mainWorld = mock(World.class);
        Location spawn = mock(Location.class);
        when(mainWorld.getSpawnLocation()).thenReturn(spawn);
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of(mainWorld));
        bukkit.when(() -> Bukkit.unloadWorld(world, false)).thenReturn(true);
    }
}
