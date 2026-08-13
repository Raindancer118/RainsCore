package de.raindancer.core.world.speedrun;

import de.raindancer.core.ui.bossbar.BarStyle;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The pre-run countdown: the shared boss bar counts down, a cue plays every second and once at
 * zero, and a participant is frozen — see {@link SpeedrunCountdown#onMove} — without touching a real
 * Paper scheduler. {@code Bukkit.getGlobalRegionScheduler().runAtFixedRate} is stubbed to hand back
 * the tick {@code Consumer} it was given, which the test then calls by hand to simulate seconds
 * passing, the same trick {@code SpeedrunResetTest} uses for {@code WorldCreator}.
 */
class SpeedrunCountdownTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private JavaPlugin plugin;
    private BossBars bossBars;
    private Effects effects;
    private PluginManager pluginManager;
    private ScheduledTask fakeTask;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        bossBars = mock(BossBars.class);
        effects = mock(Effects.class);
    }

    /** Captures the tick consumer and hands back a fake {@link ScheduledTask} so {@code cancel()} works. */
    @SuppressWarnings("unchecked")
    private Consumer<ScheduledTask> captureTicker(MockedStatic<Bukkit> bukkit) {
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
        ArgumentCaptor<Consumer<ScheduledTask>> captor = ArgumentCaptor.forClass(Consumer.class);
        fakeTask = mock(ScheduledTask.class);
        when(scheduler.runAtFixedRate(eq(plugin), captor.capture(), eq(20L), eq(20L)))
                .thenReturn(fakeTask);
        return task -> captor.getValue().accept(fakeTask);
    }

    @Test
    @DisplayName("registers itself, announces once immediately, and calls onComplete exactly once at zero")
    void countsDownFromFiveToZero() {
        try (MockedStatic<Bukkit> bukkit = mockStatic()) {
            Consumer<ScheduledTask> tick = captureTicker(bukkit);
            AtomicInteger completions = new AtomicInteger();
            SpeedrunCountdown countdown = new SpeedrunCountdown(plugin, bossBars, effects,
                    Set.of(ALICE), completions::incrementAndGet);

            countdown.begin();
            verify(pluginManager).registerEvents(countdown, plugin);
            verify(bossBars, times(1)).showShared(eq("core"), eq("speedrun-countdown"), any(),
                    any(BarStyle.class), any());
            verify(effects, times(1)).playForAll(any(), eq(Cues.COUNTDOWN));

            tick.accept(null);   // 5 -> 4
            tick.accept(null);   // 4 -> 3
            tick.accept(null);   // 3 -> 2
            tick.accept(null);   // 2 -> 1
            assertThat(completions.get()).isZero();
            verify(bossBars, times(5)).showShared(eq("core"), eq("speedrun-countdown"), any(),
                    any(BarStyle.class), any());

            tick.accept(null);   // 1 -> 0: done

            assertThat(completions.get()).isEqualTo(1);
            verify(bossBars).clearShared("core", "speedrun-countdown");
            verify(effects).playForAll(any(), eq(Cues.COUNTDOWN_DONE));
            verify(fakeTask).cancel();
        }
    }

    @Test
    @DisplayName("cancels an actual step but not a look-around, and ignores anybody not racing")
    void freezesOnlyParticipantsAndOnlyRealMovement() {
        try (MockedStatic<Bukkit> bukkit = mockStatic()) {
            captureTicker(bukkit);
            SpeedrunCountdown countdown = new SpeedrunCountdown(plugin, bossBars, effects,
                    Set.of(ALICE), () -> { });
            countdown.begin();

            World world = mock(World.class);
            Player alice = mock(Player.class);
            when(alice.getUniqueId()).thenReturn(ALICE);
            Player bob = mock(Player.class);
            when(bob.getUniqueId()).thenReturn(BOB);

            Location from = new Location(world, 10, 64, 10, 90f, 0f);
            Location lookedAround = new Location(world, 10, 64, 10, 180f, 0f);
            Location walked = new Location(world, 11, 64, 10);

            PlayerMoveEvent lookOnly = new PlayerMoveEvent(alice, from, lookedAround);
            countdown.onMove(lookOnly);
            assertThat(lookOnly.isCancelled()).isFalse();

            PlayerMoveEvent aliceWalks = new PlayerMoveEvent(alice, from, walked);
            countdown.onMove(aliceWalks);
            assertThat(aliceWalks.isCancelled()).isTrue();

            PlayerMoveEvent bobWalks = new PlayerMoveEvent(bob, from, walked);
            countdown.onMove(bobWalks);
            assertThat(bobWalks.isCancelled()).isFalse();
        }
    }

    private MockedStatic<Bukkit> mockStatic() {
        return org.mockito.Mockito.mockStatic(Bukkit.class);
    }
}
