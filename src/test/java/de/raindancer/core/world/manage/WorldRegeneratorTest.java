package de.raindancer.core.world.manage;

import de.raindancer.core.RainsCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private static Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));
        return player;
    }

    /** Every test with an occupant reaches {@code destinationFor}, which asks Core for the tracker —
     *  stubbed to "nothing remembered" unless a test says otherwise. */
    private static void stubNothingRemembered(MockedStatic<RainsCore> core) {
        RainsCore live = mock(RainsCore.class);
        WorldEntryPoints tracker = mock(WorldEntryPoints.class);
        when(tracker.before(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(live.worldEntryPoints()).thenReturn(tracker);
        core.when(RainsCore::get).thenReturn(live);
    }

    /**
     * Every {@link WorldCreator} built while this is open hands back {@code result} — and returns
     * itself from {@code environment(...)}, the way the real builder does, so the call chain a
     * regeneration makes does not fall over a mock's default {@code null}.
     */
    private static MockedConstruction<WorldCreator> creatorsMaking(World result) {
        return mockConstruction(WorldCreator.class, (creator, context) -> {
            when(creator.environment(any())).thenReturn(creator);
            when(creator.createWorld()).thenReturn(result);
        });
    }

    private static Boolean awaitResult(java.util.function.Consumer<java.util.function.Consumer<Boolean>> call) {
        AtomicReference<Boolean> result = new AtomicReference<>();
        call.accept(result::set);
        return result.get();
    }

    @Test
    @DisplayName("reads getWorldFolder() from the still-loaded World before unloading")
    void readsFolderFromTheWorldItself() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<WorldCreator> creators = creatorsMaking(mock(World.class))) {
            stubServerBasics(bukkit);

            Boolean ok = awaitResult(cb -> regenerator.regenerate(world, cb));

            assertThat(ok).isTrue();
            verify(world).getWorldFolder();
            assertThat(worldFolder).doesNotExist();
        }
    }

    /**
     * A nether that came back as an overworld would be a silent, unrecoverable swap: the folder is
     * gone by then, and nothing about the new world says it was ever meant to be anything else.
     */
    @Test
    @DisplayName("puts the world back in the environment it had, not always a plain overworld")
    void keepsTheEnvironmentItHad() {
        when(world.getEnvironment()).thenReturn(World.Environment.NETHER);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<WorldCreator> creators = creatorsMaking(mock(World.class))) {
            stubServerBasics(bukkit);

            Boolean ok = awaitResult(cb -> regenerator.regenerate(world, cb));

            assertThat(ok).isTrue();
            verify(creators.constructed().getFirst()).environment(World.Environment.NETHER);
        }
    }

    @Test
    @DisplayName("never sets a seed on the new WorldCreator")
    void neverSetsASeed() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<WorldCreator> creators = creatorsMaking(mock(World.class))) {
            stubServerBasics(bukkit);

            Boolean ok = awaitResult(cb -> regenerator.regenerate(world, cb));

            assertThat(ok).isTrue();
            assertThat(creators.constructed()).hasSize(1);
            verify(creators.constructed().getFirst(), never()).seed(anyLong());
        }
    }

    @Test
    @DisplayName("an occupant is evacuated and the regeneration still completes once they land")
    void evacuatesOccupantsThenCompletes() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
             MockedConstruction<WorldCreator> creators = creatorsMaking(mock(World.class))) {
            Player occupant = playerWithId(UUID.randomUUID());
            when(world.getPlayers()).thenReturn(List.of(occupant));
            stubServerBasics(bukkit);
            stubNothingRemembered(core);

            Boolean ok = awaitResult(cb -> regenerator.regenerate(world, cb));

            // teleportAsync resolves synchronously in this test, so the whole chain — unload, delete,
            // recreate — runs to completion in the same call, exactly as it would once a real
            // teleport's future completes on the main thread.
            assertThat(ok).isTrue();
            verify(occupant).teleportAsync(any(Location.class));
            bukkit.verify(() -> Bukkit.unloadWorld(world, false));
            assertThat(worldFolder).doesNotExist();
        }
    }

    @Test
    @DisplayName("does not unload or delete until every occupant's teleport has actually landed")
    void waitsForTeleportsBeforeUnloading() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<RainsCore> core = mockStatic(RainsCore.class)) {
            Player occupant = mock(Player.class);
            when(occupant.getUniqueId()).thenReturn(UUID.randomUUID());
            CompletableFuture<Boolean> pending = new CompletableFuture<>();
            when(occupant.teleportAsync(any(Location.class))).thenReturn(pending);
            when(world.getPlayers()).thenReturn(List.of(occupant));
            stubServerBasics(bukkit);
            stubNothingRemembered(core);

            AtomicReference<Boolean> result = new AtomicReference<>();
            regenerator.delete(world, result::set);

            assertThat(result.get()).as("nothing decided yet — the teleport has not landed").isNull();
            bukkit.verify(() -> Bukkit.unloadWorld(world, false), never());
            assertThat(worldFolder).exists();

            pending.complete(true);

            assertThat(result.get()).isTrue();
            bukkit.verify(() -> Bukkit.unloadWorld(world, false));
            assertThat(worldFolder).doesNotExist();
        }
    }

    @Test
    @DisplayName("an occupant with a remembered location is sent there, not to the generic spawn")
    void sendsOccupantToTheirRememberedLocation() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
             MockedConstruction<WorldCreator> creators = creatorsMaking(mock(World.class))) {
            UUID occupantId = UUID.randomUUID();
            Player occupant = playerWithId(occupantId);
            when(world.getPlayers()).thenReturn(List.of(occupant));
            stubServerBasics(bukkit);

            World rememberedWorld = mock(World.class);
            Location remembered = new Location(rememberedWorld, 100, 65, 100);
            RainsCore live = mock(RainsCore.class);
            WorldEntryPoints tracker = mock(WorldEntryPoints.class);
            when(tracker.before(occupantId)).thenReturn(Optional.of(remembered));
            when(live.worldEntryPoints()).thenReturn(tracker);
            core.when(RainsCore::get).thenReturn(live);

            Boolean ok = awaitResult(cb -> regenerator.regenerate(world, cb));

            assertThat(ok).isTrue();
            verify(occupant).teleportAsync(remembered);
        }
    }

    @Test
    @DisplayName("a remembered location still inside the world being deleted is not trusted")
    void ignoresARememberedLocationInTheDoomedWorldItself() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
             MockedConstruction<WorldCreator> creators = creatorsMaking(mock(World.class))) {
            UUID occupantId = UUID.randomUUID();
            Player occupant = playerWithId(occupantId);
            when(world.getPlayers()).thenReturn(List.of(occupant));

            Location remembered = new Location(world, 1, 65, 1);
            RainsCore live = mock(RainsCore.class);
            WorldEntryPoints tracker = mock(WorldEntryPoints.class);
            when(tracker.before(occupantId)).thenReturn(Optional.of(remembered));
            when(live.worldEntryPoints()).thenReturn(tracker);
            core.when(RainsCore::get).thenReturn(live);

            Location expectedFallback = mock(Location.class);
            World mainWorld = mock(World.class);
            when(mainWorld.getSpawnLocation()).thenReturn(expectedFallback);
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(mainWorld));
            bukkit.when(() -> Bukkit.unloadWorld(world, false)).thenReturn(true);

            Boolean ok = awaitResult(cb -> regenerator.regenerate(world, cb));

            assertThat(ok).isTrue();
            verify(occupant).teleportAsync(expectedFallback);
            verify(occupant, never()).teleportAsync(remembered);
        }
    }

    @Test
    @DisplayName("an empty world still regenerates as before")
    void emptyWorldStillRegenerates() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<WorldCreator> creators = creatorsMaking(mock(World.class))) {
            stubServerBasics(bukkit);

            Boolean ok = awaitResult(cb -> regenerator.regenerate(world, cb));

            assertThat(ok).isTrue();
            assertThat(worldFolder).doesNotExist();
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

            Boolean ok = awaitResult(cb -> regenerator.regenerate(world, cb));

            assertThat(ok).isFalse();
            assertThat(worldFolder).exists();
        }
    }

    @Test
    @DisplayName("a null world is refused rather than throwing")
    void nullWorldRefused() {
        assertThat(awaitResult(cb -> regenerator.regenerate(null, cb))).isFalse();
    }

    @Test
    @DisplayName("isPrimaryWorld answers true for the world at index 0, and false for any other")
    void isPrimaryWorldChecksTheFirstWorld() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            World other = mock(World.class);
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world, other));

            assertThat(WorldRegenerator.isPrimaryWorld(world)).isTrue();
            assertThat(WorldRegenerator.isPrimaryWorld(other)).isFalse();
        }
    }

    @Test
    @DisplayName("regenerate refuses the primary world without ever asking Bukkit to unload it")
    void regenerateRefusesThePrimaryWorld() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));

            Boolean ok = awaitResult(cb -> regenerator.regenerate(world, cb));

            assertThat(ok).isFalse();
            assertThat(worldFolder).exists();
            bukkit.verify(() -> Bukkit.unloadWorld(world, false), never());
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("creates a world that is not already loaded")
        void createsAnUnloadedWorld() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMaking(mock(World.class))) {
                bukkit.when(() -> Bukkit.getWorld("fresh")).thenReturn(null);

                boolean ok = regenerator.create("fresh");

                assertThat(ok).isTrue();
                assertThat(creators.constructed()).hasSize(1);
            }
        }

        @Test
        @DisplayName("refuses a world that is already loaded, without touching WorldCreator")
        void refusesAnAlreadyLoadedWorld() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class)) {
                bukkit.when(() -> Bukkit.getWorld("build")).thenReturn(world);

                boolean ok = regenerator.create("build");

                assertThat(ok).isFalse();
                assertThat(creators.constructed()).isEmpty();
            }
        }

        @Test
        @DisplayName("a blank or null name is refused rather than throwing")
        void blankNameRefused() {
            assertThat(regenerator.create(null)).isFalse();
            assertThat(regenerator.create("")).isFalse();
            assertThat(regenerator.create("   ")).isFalse();
        }

        @Test
        @DisplayName("builds the world in the environment it was asked for")
        void createsInTheGivenEnvironment() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMaking(mock(World.class))) {
                bukkit.when(() -> Bukkit.getWorld("fresh_nether")).thenReturn(null);

                boolean ok = regenerator.create("fresh_nether", World.Environment.NETHER);

                assertThat(ok).isTrue();
                verify(creators.constructed().getFirst()).environment(World.Environment.NETHER);
            }
        }

        @Test
        @DisplayName("Bukkit refusing to create it comes back false")
        void bukkitRefusalComesBackFalse() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMaking(null)) {
                bukkit.when(() -> Bukkit.getWorld("stubborn")).thenReturn(null);

                assertThat(regenerator.create("stubborn")).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("unloads and deletes the folder, without recreating anything")
        void deletesWithoutRecreating() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class)) {
                stubServerBasics(bukkit);

                Boolean ok = awaitResult(cb -> regenerator.delete(world, cb));

                assertThat(ok).isTrue();
                assertThat(worldFolder).doesNotExist();
                assertThat(creators.constructed()).isEmpty();
            }
        }

        @Test
        @DisplayName("refuses the primary world without ever asking Bukkit to unload it")
        void refusesThePrimaryWorld() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));

                Boolean ok = awaitResult(cb -> regenerator.delete(world, cb));

                assertThat(ok).isFalse();
                assertThat(worldFolder).exists();
                bukkit.verify(() -> Bukkit.unloadWorld(world, false), never());
            }
        }

        @Test
        @DisplayName("a null world is refused rather than throwing")
        void nullWorldRefused() {
            assertThat(awaitResult(cb -> regenerator.delete(null, cb))).isFalse();
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
