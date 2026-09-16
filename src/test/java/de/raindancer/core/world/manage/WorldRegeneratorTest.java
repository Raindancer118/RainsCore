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
import java.util.ArrayList;
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
            when(creator.seed(anyLong())).thenReturn(creator);
            when(creator.copy(any(World.class))).thenReturn(creator);
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
    @DisplayName("a random regeneration draws a new seed rather than keeping the one the copied creator carries")
    void randomIsANewSeed() {
        // WorldCreator#copy brings the old seed along with the generator and the world type. Leaving the
        // seed alone after that would quietly regenerate the same map every time.
        when(world.getSeed()).thenReturn(777L);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<WorldCreator> creators = creatorsMaking(mock(World.class))) {
            stubServerBasics(bukkit);

            Boolean ok = awaitResult(cb -> regenerator.regenerate(world, cb));

            assertThat(ok).isTrue();
            assertThat(creators.constructed()).hasSize(1);
            org.mockito.ArgumentCaptor<Long> seed = org.mockito.ArgumentCaptor.forClass(Long.class);
            verify(creators.constructed().getFirst()).seed(seed.capture());
            assertThat(seed.getValue()).isNotEqualTo(777L);
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
            UUID rememberedId = UUID.randomUUID();
            when(rememberedWorld.getUID()).thenReturn(rememberedId);
            bukkit.when(() -> Bukkit.getWorld(rememberedId)).thenReturn(rememberedWorld);
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
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<WorldCreator> creators = creatorsMaking(mock(World.class))) {
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

    /**
     * A speedrun resets its overworld before its nether. Whoever is still in the nether then entered it
     * from an overworld that no longer exists, and asking that Location for its world throws "World
     * unloaded" — which used to abort the nether's reset and never reach the end at all.
     */
    @Test
    @DisplayName("a remembered place in a world that has since been unloaded falls back to spawn")
    void aRememberedPlaceInAnUnloadedWorld() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
             MockedConstruction<WorldCreator> creators = creatorsMaking(mock(World.class))) {
            UUID occupantId = UUID.randomUUID();
            Player occupant = playerWithId(occupantId);
            when(world.getPlayers()).thenReturn(List.of(occupant));
            stubServerBasics(bukkit);
            Location mainSpawn = Bukkit.getWorlds().getFirst().getSpawnLocation();

            Location gone = mock(Location.class);
            when(gone.isWorldLoaded()).thenReturn(false);
            when(gone.getWorld()).thenThrow(new IllegalArgumentException("World unloaded"));
            RainsCore live = mock(RainsCore.class);
            WorldEntryPoints tracker = mock(WorldEntryPoints.class);
            when(tracker.before(occupantId)).thenReturn(Optional.of(gone));
            when(live.worldEntryPoints()).thenReturn(tracker);
            core.when(RainsCore::get).thenReturn(live);

            Boolean ok = awaitResult(cb -> regenerator.regenerate(world, cb));

            assertThat(ok).isTrue();
            verify(occupant).teleportAsync(mainSpawn);
        }
    }

    /**
     * Experience rather than theory: a flat or amplified world, or one with a generator plugin, that came
     * back as plain default terrain — and the owner's game rules and border gone with the old folder.
     */
    @Nested
    @DisplayName("what an owner set up survives a regeneration")
    class Preserved {

        @Test
        @DisplayName("the new world is made from a copy of the old one's creator settings")
        void copiesTheCreator() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMaking(mock(World.class))) {
                stubServerBasics(bukkit);

                awaitResult(cb -> regenerator.regenerate(world, WorldSeed.random(), cb));

                // Read while the old world was still loaded: generator, world type, structures,
                // hardcore — everything WorldCreator#copy knows how to carry.
                verify(creators.constructed().getFirst()).copy(world);
            }
        }

        @Test
        @DisplayName("game rules come across to the new world")
        void gameRulesComeAcross() {
            when(world.getGameRules()).thenReturn(new String[]{"keepInventory"});
            when(world.getGameRuleValue("keepInventory")).thenReturn("true");
            World made = mock(World.class);
            when(made.getWorldBorder()).thenReturn(mock(org.bukkit.WorldBorder.class));
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMaking(made)) {
                stubServerBasics(bukkit);

                Boolean ok = awaitResult(cb -> regenerator.regenerate(world, WorldSeed.same(), cb));

                assertThat(ok).isTrue();
                verify(made).setGameRuleValue("keepInventory", "true");
            }
        }

        @Test
        @DisplayName("the primary level's own nether and end are refused, like the primary world is")
        void refusesTheServersOwnDimensions() {
            when(world.getKey()).thenReturn(org.bukkit.NamespacedKey.minecraft("the_nether"));
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubServerBasics(bukkit);

                Boolean ok = awaitResult(cb -> regenerator.regenerate(world, cb));

                assertThat(ok).isFalse();
                assertThat(worldFolder).exists();
                bukkit.verify(() -> Bukkit.unloadWorld(world, false), never());
                assertThat(WorldRegenerator.isServerDimension(world)).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("choosing the seed")
    class Seeds {

        private final SeedHistory history = mock(SeedHistory.class);
        private final WorldRegenerator recording = new WorldRegenerator(history);

        @BeforeEach
        void seedsAreWritten() {
            when(history.flushNow()).thenReturn(CompletableFuture.completedFuture(true));
        }

        private World madeWithSeed(long seed) {
            World made = mock(World.class);
            when(made.getSeed()).thenReturn(seed);
            return made;
        }

        @Test
        @DisplayName("the same seed puts the map back exactly as it was generated")
        void sameSeedIsTheOldOne() {
            when(world.getSeed()).thenReturn(777L);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMaking(madeWithSeed(777L))) {
                stubServerBasics(bukkit);

                Boolean ok = awaitResult(cb -> recording.regenerate(world, WorldSeed.same(), cb));

                assertThat(ok).isTrue();
                verify(creators.constructed().getFirst()).seed(777L);
            }
        }

        @Test
        @DisplayName("a chosen seed is the seed the new world is made with")
        void aFixedSeed() {
            when(world.getSeed()).thenReturn(777L);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMaking(madeWithSeed(42L))) {
                stubServerBasics(bukkit);

                Boolean ok = awaitResult(cb -> recording.regenerate(world, WorldSeed.fixed(42L), cb));

                assertThat(ok).isTrue();
                verify(creators.constructed().getFirst()).seed(42L);
            }
        }

        @Test
        @DisplayName("a random seed is a fresh one, not the seed of the world it replaces")
        void randomSetsAFreshSeed() {
            when(world.getSeed()).thenReturn(777L);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMaking(madeWithSeed(5L))) {
                stubServerBasics(bukkit);

                Boolean ok = awaitResult(cb -> recording.regenerate(world, WorldSeed.random(), cb));

                assertThat(ok).isTrue();
                verify(creators.constructed().getFirst(), never()).seed(777L);
            }
        }

        @Test
        @DisplayName("the history gets the seed being thrown away and the seed that replaced it")
        void bothEndsAreRecorded() {
            when(world.getSeed()).thenReturn(777L);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMaking(madeWithSeed(5L))) {
                stubServerBasics(bukkit);

                awaitResult(cb -> recording.regenerate(world, WorldSeed.random(), cb));

                // The outgoing seed is recorded before anything is deleted: a world that predates the
                // history would otherwise lose the one seed nobody wrote down anywhere else.
                org.mockito.InOrder order = org.mockito.Mockito.inOrder(history);
                order.verify(history).record("build", 777L, SeedHistory.Cause.REPLACED);
                order.verify(history).record("build", 5L, SeedHistory.Cause.REGENERATED);
            }
        }

        @Test
        @DisplayName("a regeneration that failed to delete records the old seed and no new one")
        void aFailureRecordsNoNewSeed() {
            when(world.getSeed()).thenReturn(777L);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMaking(madeWithSeed(5L))) {
                stubServerBasics(bukkit);
                bukkit.when(() -> Bukkit.unloadWorld(world, false)).thenReturn(false);

                Boolean ok = awaitResult(cb -> recording.regenerate(world, WorldSeed.random(), cb));

                assertThat(ok).isFalse();
                verify(history).record("build", 777L, SeedHistory.Cause.REPLACED);
                verify(history, never()).record(any(), org.mockito.ArgumentMatchers.eq(5L), any());
            }
        }

        @Test
        @DisplayName("creating a new world takes a seed too, and records the one it got")
        void createWithASeed() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMaking(madeWithSeed(99L))) {

                boolean ok = recording.create("fresh", World.Environment.THE_END, WorldSeed.fixed(99L));

                assertThat(ok).isTrue();
                verify(creators.constructed().getFirst()).seed(99L);
                verify(creators.constructed().getFirst()).environment(World.Environment.THE_END);
                verify(history).record("fresh", 99L, SeedHistory.Cause.CREATED);
            }
        }

        @Test
        @DisplayName("deleting a world writes its seed down first — afterwards it exists nowhere")
        void deletingRecordsTheSeed() {
            when(world.getSeed()).thenReturn(4242L);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubServerBasics(bukkit);

                Boolean ok = awaitResult(cb -> recording.delete(world, cb));

                assertThat(ok).isTrue();
                verify(history).record("build", 4242L, SeedHistory.Cause.DELETED);
            }
        }

        @Test
        @DisplayName("a regeneration records the old seed once, as replaced rather than deleted")
        void regeneratingIsNotDeleting() {
            when(world.getSeed()).thenReturn(4242L);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMaking(madeWithSeed(1L))) {
                stubServerBasics(bukkit);

                awaitResult(cb -> recording.regenerate(world, WorldSeed.random(), cb));

                verify(history, never()).record(any(), anyLong(), org.mockito.ArgumentMatchers.eq(SeedHistory.Cause.DELETED));
                verify(history, org.mockito.Mockito.times(1)).record("build", 4242L, SeedHistory.Cause.REPLACED);
            }
        }

        @Test
        @DisplayName("loading a world that already exists on disk writes nothing into the history")
        void loadingIsNotCreating() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMaking(madeWithSeed(3L))) {

                boolean ok = recording.load("kept", World.Environment.NETHER);

                assertThat(ok).isTrue();
                verify(creators.constructed().getFirst()).environment(World.Environment.NETHER);
                verify(creators.constructed().getFirst(), never()).seed(anyLong());
                verify(history, never()).record(any(), anyLong(), any());
            }
        }

        @Test
        @DisplayName("without a history nothing is recorded and nothing breaks")
        void noHistory() {
            when(world.getSeed()).thenReturn(1L);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMaking(madeWithSeed(1L))) {
                stubServerBasics(bukkit);

                Boolean ok = awaitResult(cb -> regenerator.regenerate(world, WorldSeed.same(), cb));

                assertThat(ok).isTrue();
                verify(creators.constructed().getFirst()).seed(1L);
            }
        }
    }

    /**
     * Resetting worlds that belong together — a speedrun's overworld, nether and end — as one operation.
     * Done one at a time, whoever stood in the second world was sent back to the first, already gone.
     */
    @Nested
    @DisplayName("regenerating a group of worlds together")
    class Groups {

        @TempDir
        Path groupDirectory;

        private final SeedHistory history = mock(SeedHistory.class);
        private final WorldRegenerator recording = new WorldRegenerator(history);

        private World overworld;
        private World nether;
        private World end;

        private World worldAt(String name, World.Environment environment, long seed) throws IOException {
            Path folder = groupDirectory.resolve(name);
            Files.createDirectories(folder.resolve("region"));
            Files.writeString(folder.resolve("level.dat"), "marker");
            World made = mock(World.class);
            when(made.getName()).thenReturn(name);
            when(made.getWorldFolder()).thenReturn(folder.toFile());
            when(made.getEnvironment()).thenReturn(environment);
            when(made.getSeed()).thenReturn(seed);
            when(made.getPlayers()).thenReturn(List.of());
            return made;
        }

        @BeforeEach
        void worlds() throws IOException {
            when(history.flushNow()).thenReturn(CompletableFuture.completedFuture(true));
            overworld = worldAt("run", World.Environment.NORMAL, 11L);
            nether = worldAt("run_nether", World.Environment.NETHER, 11L);
            end = worldAt("run_the_end", World.Environment.THE_END, 11L);
        }

        private Location stubServer(MockedStatic<Bukkit> bukkit, MockedStatic<RainsCore> core) {
            World mainWorld = mock(World.class);
            Location spawn = mock(Location.class);
            when(mainWorld.getSpawnLocation()).thenReturn(spawn);
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(mainWorld, overworld, nether, end));
            for (World each : List.of(overworld, nether, end)) {
                bukkit.when(() -> Bukkit.unloadWorld(each, false)).thenReturn(true);
            }
            RainsCore live = mock(RainsCore.class);
            WorldEntryPoints tracker = mock(WorldEntryPoints.class);
            when(tracker.before(any())).thenReturn(Optional.empty());
            when(live.worldEntryPoints()).thenReturn(tracker);
            core.when(RainsCore::get).thenReturn(live);
            return spawn;
        }

        private static MockedConstruction<WorldCreator> creators() {
            return mockConstruction(WorldCreator.class, (creator, context) -> {
                when(creator.environment(any())).thenReturn(creator);
                when(creator.seed(anyLong())).thenReturn(creator);
                when(creator.copy(any(World.class))).thenReturn(creator);
                World made = mock(World.class);
                when(made.getSeed()).thenReturn(123L);
                when(creator.createWorld()).thenReturn(made);
            });
        }

        @Test
        @DisplayName("all of them are deleted and come back, each as the dimension it was")
        void allComeBack() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
                 MockedConstruction<WorldCreator> creators = creators()) {
                stubServer(bukkit, core);

                Boolean ok = awaitResult(cb -> recording.regenerateAll(List.of(overworld, nether, end),
                        WorldSeed.fixed(5L), cb));

                assertThat(ok).isTrue();
                assertThat(groupDirectory.resolve("run")).doesNotExist();
                assertThat(groupDirectory.resolve("run_nether")).doesNotExist();
                assertThat(groupDirectory.resolve("run_the_end")).doesNotExist();
                assertThat(creators.constructed()).hasSize(3);
                verify(creators.constructed().get(0)).environment(World.Environment.NORMAL);
                verify(creators.constructed().get(1)).environment(World.Environment.NETHER);
                verify(creators.constructed().get(2)).environment(World.Environment.THE_END);
                creators.constructed().forEach(creator -> verify(creator).seed(5L));
            }
        }

        @Test
        @DisplayName("somebody in the nether who came from the run's overworld is sent out of the group")
        void nobodyIsSentIntoAnotherDoomedWorld() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
                 MockedConstruction<WorldCreator> creators = creators()) {
                Location spawn = stubServer(bukkit, core);
                UUID racerId = UUID.randomUUID();
                Player racer = playerWithId(racerId);
                when(nether.getPlayers()).thenReturn(List.of(racer));

                UUID overworldId = UUID.randomUUID();
                when(overworld.getUID()).thenReturn(overworldId);
                bukkit.when(() -> Bukkit.getWorld(overworldId)).thenReturn(overworld);
                Location cameFrom = new Location(overworld, 0, 70, 0);
                when(RainsCore.get().worldEntryPoints().before(racerId)).thenReturn(Optional.of(cameFrom));

                Boolean ok = awaitResult(cb -> recording.regenerateAll(List.of(overworld, nether, end),
                        WorldSeed.random(), cb));

                assertThat(ok).isTrue();
                verify(racer).teleportAsync(spawn);
                verify(racer, never()).teleportAsync(cameFrom);
            }
        }

        @Test
        @DisplayName("nothing is unloaded until every occupant of every world has landed")
        void waitsForEveryoneFirst() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
                 MockedConstruction<WorldCreator> creators = creators()) {
                stubServer(bukkit, core);
                Player inTheEnd = mock(Player.class);
                when(inTheEnd.getUniqueId()).thenReturn(UUID.randomUUID());
                CompletableFuture<Boolean> landing = new CompletableFuture<>();
                when(inTheEnd.teleportAsync(any(Location.class))).thenReturn(landing);
                when(end.getPlayers()).thenReturn(List.of(inTheEnd));

                AtomicReference<Boolean> result = new AtomicReference<>();
                recording.regenerateAll(List.of(overworld, nether, end), WorldSeed.random(), result::set);

                bukkit.verify(() -> Bukkit.unloadWorld(any(World.class), org.mockito.ArgumentMatchers.anyBoolean()), never());
                assertThat(result.get()).isNull();

                landing.complete(true);

                assertThat(result.get()).isTrue();
                bukkit.verify(() -> Bukkit.unloadWorld(overworld, false));
            }
        }

        @Test
        @DisplayName("a random seed is one seed for the whole group, so the dimensions still belong together")
        void oneRandomSeedForAll() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
                 MockedConstruction<WorldCreator> creators = creators()) {
                stubServer(bukkit, core);

                awaitResult(cb -> recording.regenerateAll(List.of(overworld, nether, end), WorldSeed.random(), cb));

                org.mockito.ArgumentCaptor<Long> seeds = org.mockito.ArgumentCaptor.forClass(Long.class);
                for (WorldCreator creator : creators.constructed()) {
                    verify(creator).seed(seeds.capture());
                }
                assertThat(seeds.getAllValues()).hasSize(3).containsOnly(seeds.getAllValues().getFirst());
                assertThat(seeds.getAllValues().getFirst()).isNotEqualTo(11L);
            }
        }

        @Test
        @DisplayName("the same seed keeps each world's own, and every seed is written down on both sides")
        void sameSeedAndHistory() {
            when(nether.getSeed()).thenReturn(22L);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
                 MockedConstruction<WorldCreator> creators = creators()) {
                stubServer(bukkit, core);

                awaitResult(cb -> recording.regenerateAll(List.of(overworld, nether), WorldSeed.same(), cb));

                verify(creators.constructed().get(0)).seed(11L);
                verify(creators.constructed().get(1)).seed(22L);
                verify(history).record("run", 11L, SeedHistory.Cause.REPLACED);
                verify(history).record("run_nether", 22L, SeedHistory.Cause.REPLACED);
                verify(history).record("run", 123L, SeedHistory.Cause.REGENERATED);
                verify(history).record("run_nether", 123L, SeedHistory.Cause.REGENERATED);
            }
        }

        @Test
        @DisplayName("a group containing the primary world is refused before anybody is moved")
        void refusesThePrimaryWorld() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
                 MockedConstruction<WorldCreator> creators = creators()) {
                stubServer(bukkit, core);
                bukkit.when(Bukkit::getWorlds).thenReturn(List.of(overworld, nether, end));
                Player racer = playerWithId(UUID.randomUUID());
                when(nether.getPlayers()).thenReturn(List.of(racer));

                Boolean ok = awaitResult(cb -> recording.regenerateAll(List.of(overworld, nether, end),
                        WorldSeed.random(), cb));

                assertThat(ok).isFalse();
                verify(racer, never()).teleportAsync(any(Location.class));
                bukkit.verify(() -> Bukkit.unloadWorld(any(World.class), org.mockito.ArgumentMatchers.anyBoolean()), never());
            }
        }

        @Test
        @DisplayName("one world refusing to unload does not stop the others, and the answer says so")
        void oneFailureIsNotAllFailures() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
                 MockedConstruction<WorldCreator> creators = creators()) {
                stubServer(bukkit, core);
                bukkit.when(() -> Bukkit.unloadWorld(nether, false)).thenReturn(false);

                Boolean ok = awaitResult(cb -> recording.regenerateAll(List.of(overworld, nether, end),
                        WorldSeed.random(), cb));

                assertThat(ok).isFalse();
                assertThat(groupDirectory.resolve("run_nether")).exists();
                assertThat(groupDirectory.resolve("run_the_end")).doesNotExist();
                verify(creators.constructed().get(0)).createWorld();
                verify(creators.constructed().get(1), never()).createWorld();
                verify(creators.constructed().get(2)).createWorld();
            }
        }

        @Test
        @DisplayName("deleting a group moves everybody out of all of it first, and writes every seed down")
        void deletingAGroup() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
                 MockedConstruction<WorldCreator> creators = creators()) {
                Location spawn = stubServer(bukkit, core);
                UUID racerId = UUID.randomUUID();
                Player racer = playerWithId(racerId);
                when(end.getPlayers()).thenReturn(List.of(racer));
                UUID overworldId = UUID.randomUUID();
                when(overworld.getUID()).thenReturn(overworldId);
                bukkit.when(() -> Bukkit.getWorld(overworldId)).thenReturn(overworld);
                when(RainsCore.get().worldEntryPoints().before(racerId))
                        .thenReturn(Optional.of(new Location(overworld, 0, 70, 0)));

                Boolean ok = awaitResult(cb -> recording.deleteAll(List.of(overworld, nether, end), cb));

                assertThat(ok).isTrue();
                verify(racer).teleportAsync(spawn);
                assertThat(groupDirectory.resolve("run")).doesNotExist();
                assertThat(groupDirectory.resolve("run_the_end")).doesNotExist();
                assertThat(creators.constructed()).isEmpty();
                verify(history).record("run", 11L, SeedHistory.Cause.DELETED);
                verify(history).record("run_the_end", 11L, SeedHistory.Cause.DELETED);
            }
        }

        @Test
        @DisplayName("a teleport that comes back false stops everything: nothing unloaded, nothing deleted")
        void aRefusedTeleportIsNotAnEvacuation() {
            // Paper answers false for a cross-world teleport it would not do — somebody with a passenger,
            // for one. Treating "the future finished" as "they left" deleted the world around them.
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
                 MockedConstruction<WorldCreator> creators = creators()) {
                stubServer(bukkit, core);
                Player stuck = mock(Player.class);
                when(stuck.getUniqueId()).thenReturn(UUID.randomUUID());
                when(stuck.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(false));
                when(nether.getPlayers()).thenReturn(List.of(stuck));

                Boolean regenerated = awaitResult(cb -> recording.regenerateAll(List.of(overworld, nether, end),
                        WorldSeed.random(), cb));
                Boolean deleted = awaitResult(cb -> recording.deleteAll(List.of(overworld, nether, end), cb));

                assertThat(regenerated).isFalse();
                assertThat(deleted).isFalse();
                bukkit.verify(() -> Bukkit.unloadWorld(any(World.class), org.mockito.ArgumentMatchers.anyBoolean()), never());
                assertThat(groupDirectory.resolve("run_nether")).exists();
            }
        }

        @Test
        @DisplayName("nothing is unloaded until the outgoing seeds are actually on disk")
        void seedsFirst() {
            CompletableFuture<Boolean> written = new CompletableFuture<>();
            when(history.flushNow()).thenReturn(written);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
                 MockedConstruction<WorldCreator> creators = creators()) {
                stubServer(bukkit, core);

                AtomicReference<Boolean> result = new AtomicReference<>();
                recording.regenerateAll(List.of(overworld, nether), WorldSeed.random(), result::set);

                bukkit.verify(() -> Bukkit.unloadWorld(any(World.class), org.mockito.ArgumentMatchers.anyBoolean()), never());
                written.complete(true);
                assertThat(result.get()).isTrue();
            }
        }

        @Test
        @DisplayName("a seed that could not be written stops the deletion — the seed would exist nowhere")
        void unwrittenSeedStopsIt() {
            when(history.flushNow()).thenReturn(CompletableFuture.completedFuture(false));
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
                 MockedConstruction<WorldCreator> creators = creators()) {
                stubServer(bukkit, core);

                Boolean ok = awaitResult(cb -> recording.deleteAll(List.of(overworld), cb));

                assertThat(ok).isFalse();
                assertThat(groupDirectory.resolve("run")).exists();
            }
        }

        @Test
        @DisplayName("unloading, deleting and creating happen on the thread handed in for world work")
        void worldWorkOnTheGlobalThread() {
            List<String> ran = new ArrayList<>();
            WorldRegenerator onAThread = new WorldRegenerator(history, task -> {
                ran.add("global");
                task.run();
            });
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedStatic<RainsCore> core = mockStatic(RainsCore.class);
                 MockedConstruction<WorldCreator> creators = creators()) {
                stubServer(bukkit, core);

                Boolean ok = awaitResult(cb -> onAThread.regenerateAll(List.of(overworld), WorldSeed.random(), cb));

                assertThat(ok).isTrue();
                assertThat(ran).isNotEmpty();
            }
        }

        @Test
        @DisplayName("nothing to regenerate is refused rather than reported as done")
        void empty() {
            assertThat(awaitResult(cb -> recording.regenerateAll(List.of(), WorldSeed.random(), cb))).isFalse();
            assertThat(awaitResult(cb -> recording.regenerateAll(null, WorldSeed.random(), cb))).isFalse();
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
