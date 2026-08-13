package de.raindancer.core.world.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.bukkit.Server;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpeedrunLobbyTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @TempDir
    Path dataFolder;

    private JavaPlugin plugin;
    private PluginManager pluginManager;
    private SettingsStore<SpeedrunSettings> settings;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);

        settings = new SettingsStore<>(
                SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS),
                dataFolder.resolve("speedrun.yml"));
        settings.load();
    }

    private SpeedrunLobby lobby() {
        return new SpeedrunLobby(plugin, settings);
    }

    private SpeedrunLobby lobbyWithCountdown(SpeedrunCountdownLauncher launcher) {
        return new SpeedrunLobby(plugin, settings, new SpeedrunReset(), launcher);
    }

    private SpeedrunLobby lobbyWithMessages(Messages messages) {
        // A launcher that finishes immediately, as if the countdown had reached zero — start() is
        // what actually wires up the onFinish announcement this is testing.
        return new SpeedrunLobby(plugin, settings, new SpeedrunReset(),
                (participants, onComplete) -> onComplete.run(), messages);
    }

    @Test
    @DisplayName("a fresh lobby with no session is READY")
    void freshLobbyIsReady() {
        assertThat(lobby().state()).isEqualTo(SpeedrunLobbyState.READY);
    }

    @Nested
    @DisplayName("beginCountdown")
    class BeginCountdown {

        @Test
        @DisplayName("refuses without ever touching the launcher when there is no end condition")
        void refusesWithoutLaunchingWhenHopeless() {
            settings.set("advancement-key", "");
            settings.set("death-policy", "OFF");
            java.util.concurrent.atomic.AtomicBoolean launched = new java.util.concurrent.atomic.AtomicBoolean();
            SpeedrunLobby lobby = lobbyWithCountdown((participants, onComplete) -> launched.set(true));

            SpeedrunLobby.StartOutcome outcome = lobby.beginCountdown(Set.of(ALICE));

            assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.NO_END_CONDITION);
            assertThat(launched).isFalse();
        }

        @Test
        @DisplayName("reports COUNTDOWN once launched, before the launcher completes")
        void reportsCountdownWhileWaiting() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                // Never calls onComplete — simulates the countdown still ticking.
                SpeedrunLobby lobby = lobbyWithCountdown((participants, onComplete) -> { });

                SpeedrunLobby.StartOutcome outcome = lobby.beginCountdown(Set.of(ALICE));

                assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.STARTED);
                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.COUNTDOWN);
                assertThat(lobby.session()).isEmpty();
            }
        }

        @Test
        @DisplayName("refuses a second press while one is already counting down")
        void refusesWhileCountingDown() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                java.util.concurrent.atomic.AtomicInteger launches = new java.util.concurrent.atomic.AtomicInteger();
                SpeedrunLobby lobby = lobbyWithCountdown(
                        (participants, onComplete) -> launches.incrementAndGet());

                lobby.beginCountdown(Set.of(ALICE));
                SpeedrunLobby.StartOutcome second = lobby.beginCountdown(Set.of(BOB));

                assertThat(second).isEqualTo(SpeedrunLobby.StartOutcome.NOT_READY);
                assertThat(launches.get()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("once the launcher completes, the run actually starts")
        void startsForRealOnceTheCountdownCompletes() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                // A fake that finishes immediately, as if the countdown had reached zero.
                SpeedrunLobby lobby = lobbyWithCountdown(
                        (participants, onComplete) -> onComplete.run());

                SpeedrunLobby.StartOutcome outcome = lobby.beginCountdown(Set.of(ALICE, BOB));

                assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.STARTED);
                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.RUNNING);
                assertThat(lobby.session()).isPresent();
                assertThat(lobby.session().orElseThrow().participants())
                        .containsExactlyInAnyOrder(ALICE, BOB);
            }
        }

        @Test
        @DisplayName("without a launcher at all, refuses rather than throwing")
        void refusesGracefullyWithNoLauncher() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                SpeedrunLobby lobby = lobby();   // the 2-arg constructor has no countdown launcher

                SpeedrunLobby.StartOutcome outcome = lobby.beginCountdown(Set.of(ALICE));

                assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.NOT_READY);
                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.READY);
            }
        }
    }

    @Nested
    @DisplayName("starting")
    class Starting {

        @Test
        @DisplayName("refuses when neither an advancement nor a death policy is configured")
        void refusesWithNoEndCondition() {
            settings.set("advancement-key", "");
            settings.set("death-policy", "OFF");
            SpeedrunLobby lobby = lobby();

            SpeedrunLobby.StartOutcome outcome = lobby.start(Set.of(ALICE));

            assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.NO_END_CONDITION);
            assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.READY);
        }

        @Test
        @DisplayName("refuses an empty roster")
        void refusesEmptyRoster() {
            SpeedrunLobby lobby = lobby();

            SpeedrunLobby.StartOutcome outcome = lobby.start(Set.of());

            assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.NO_PARTICIPANTS);
        }

        @Test
        @DisplayName("refuses when the configured world is not loaded")
        void refusesMissingWorld() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
                SpeedrunLobby lobby = lobby();

                SpeedrunLobby.StartOutcome outcome = lobby.start(Set.of(ALICE));

                assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.WORLD_MISSING);
            }
        }

        @Test
        @DisplayName("succeeds and moves the lobby to RUNNING")
        void succeeds() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                SpeedrunLobby lobby = lobby();

                SpeedrunLobby.StartOutcome outcome = lobby.start(Set.of(ALICE, BOB));

                assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.STARTED);
                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.RUNNING);
                assertThat(lobby.session()).isPresent();
                assertThat(lobby.session().orElseThrow().participants()).containsExactlyInAnyOrder(ALICE, BOB);
            }
        }

        @Test
        @DisplayName("refuses a second start while a run is already under way")
        void refusesWhileAlreadyRunning() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                SpeedrunLobby lobby = lobby();
                lobby.start(Set.of(ALICE));

                SpeedrunLobby.StartOutcome second = lobby.start(Set.of(BOB));

                assertThat(second).isEqualTo(SpeedrunLobby.StartOutcome.NOT_READY);
                assertThat(lobby.session().orElseThrow().participants()).containsExactly(ALICE);
            }
        }
    }

    @Nested
    @DisplayName("announcing a finish")
    class AnnouncingFinish {

        @Test
        @DisplayName("tells every online participant the reason and the time, in chat")
        void tellsOnlineParticipants() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                Player alice = mock(Player.class);
                bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);
                bukkit.when(() -> Bukkit.getPlayer(BOB)).thenReturn(null);   // Bob already left
                Messages messages = mock(Messages.class);
                SpeedrunLobby lobby = lobbyWithMessages(messages);

                lobby.beginCountdown(Set.of(ALICE, BOB));
                lobby.session().orElseThrow().finish("advancement:minecraft:end/kill_dragon");

                verify(messages).send(eq(alice), eq("speedrun.finished"),
                        eq("reason"), any(), eq("time"), any());
            }
        }

        @Test
        @DisplayName("a lobby built without Messages does not throw when a run finishes")
        void doesNotThrowWithoutMessages() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                SpeedrunLobby lobby = lobby();
                lobby.start(Set.of(ALICE));

                assertThatCode(() -> lobby.session().orElseThrow().finish("done"))
                        .doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("auto-reset once a finished run is abandoned")
    class AutoReset {

        @Test
        @DisplayName("does nothing while a participant is still online")
        void doesNothingWhileSomebodyRemains() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
                SpeedrunLobby lobby = lobby();
                lobby.start(Set.of(ALICE, BOB));
                lobby.session().orElseThrow().finish("done");
                bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(null);
                bukkit.when(() -> Bukkit.getPlayer(BOB)).thenReturn(mock(org.bukkit.entity.Player.class));

                lobby.resetIfAbandoned(ALICE);

                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.FINISHED);
            }
        }

        @Test
        @DisplayName("regenerates the world and returns to READY once the last participant leaves")
        void resetsOnceEverybodyIsGone() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class,
                         (mockCreator, context) -> when(mockCreator.createWorld())
                                 .thenReturn(mock(World.class)))) {
                World world = mock(World.class);
                World mainWorld = mock(World.class);
                Location spawn = mock(Location.class);
                when(mainWorld.getSpawnLocation()).thenReturn(spawn);
                when(world.getName()).thenReturn("world");
                when(world.getWorldFolder()).thenReturn(dataFolder.resolve("speedrun").toFile());
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
                bukkit.when(Bukkit::getWorlds).thenReturn(List.of(mainWorld));
                bukkit.when(() -> Bukkit.unloadWorld(world, false)).thenReturn(true);

                SpeedrunLobby lobby = lobby();
                lobby.start(Set.of(ALICE));
                lobby.session().orElseThrow().finish("done");
                bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(null);

                lobby.resetIfAbandoned(ALICE);

                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.READY);
                assertThat(lobby.session()).isEmpty();
            }
        }

        @Test
        @DisplayName("does nothing while the lobby is not FINISHED")
        void doesNothingWhenNotFinished() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                SpeedrunLobby lobby = lobby();
                lobby.start(Set.of(ALICE));

                lobby.resetIfAbandoned(ALICE);

                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.RUNNING);
            }
        }
    }
}
