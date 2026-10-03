package de.raindancer.core.platform.util;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Paper's schedulers refuse every task from a plugin that is disabled — and onDisable runs after the
 * plugin is marked disabled. Every cleanup that hops threads on the way out (show the hidden, release
 * force-loaded chunks, give items back, close windows) used to throw there and take the rest of
 * onDisable with it. A one-shot task for a disabled plugin is run in place instead: shutdown is the
 * one moment nothing else is ticking, and nothing would ever run it later.
 */
class SchedulingTest {

    private final Plugin plugin = mock(Plugin.class);
    private final Player player = mock(Player.class);
    private final Location at = mock(Location.class);
    private final EntityScheduler entities = mock(EntityScheduler.class);
    private final RegionScheduler regions = mock(RegionScheduler.class);
    private final GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
    private final AsyncScheduler async = mock(AsyncScheduler.class);
    private final AtomicInteger ran = new AtomicInteger();
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getRegionScheduler).thenReturn(regions);
        bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
        bukkit.when(Bukkit::getAsyncScheduler).thenReturn(async);
        when(player.getScheduler()).thenReturn(entities);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Nested
    @DisplayName("for a plugin that is being disabled")
    class Disabled {

        @BeforeEach
        void disabled() {
            when(plugin.isEnabled()).thenReturn(false);
        }

        @Test
        @DisplayName("every one-shot task runs in place, and no scheduler is asked")
        void runsInPlace() {
            Scheduling.entity(plugin, player, ran::incrementAndGet);
            Scheduling.entityLater(plugin, player, 20, ran::incrementAndGet);
            Scheduling.region(plugin, at, ran::incrementAndGet);
            Scheduling.global(plugin, ran::incrementAndGet);
            Scheduling.globalLater(plugin, 20, ran::incrementAndGet);
            Scheduling.async(plugin, ran::incrementAndGet);
            Scheduling.asyncLater(plugin, 5, TimeUnit.SECONDS, ran::incrementAndGet);

            assertThat(ran).hasValue(7);
            verifyNoInteractions(entities, regions, global, async);
        }

        @Test
        @DisplayName("a task that throws does not take the rest of onDisable with it")
        void aFailureStaysInside() {
            assertThatCode(() -> Scheduling.global(plugin, () -> {
                throw new IllegalStateException("gone");
            })).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("isLive says so, for a caller with its own decision to make")
        void tellsTheCaller() {
            assertThat(Scheduling.isLive(plugin)).isFalse();
        }
    }

    @Nested
    @DisplayName("for a running plugin")
    class Enabled {

        @BeforeEach
        void enabled() {
            when(plugin.isEnabled()).thenReturn(true);
        }

        @Test
        @DisplayName("tasks go to the scheduler that owns the thread, exactly as before")
        void scheduled() {
            Scheduling.entity(plugin, player, ran::incrementAndGet);
            Scheduling.region(plugin, at, ran::incrementAndGet);
            Scheduling.global(plugin, ran::incrementAndGet);
            Scheduling.async(plugin, ran::incrementAndGet);

            assertThat(ran).hasValue(0);
            verify(entities).run(eq(plugin), any(), any());
            verify(regions).execute(eq(plugin), eq(at), any());
            verify(global).execute(eq(plugin), any());
            verify(async).runNow(eq(plugin), any());
            verify(entities, never()).runDelayed(any(), any(), any(), anyLong());
        }
    }
}
