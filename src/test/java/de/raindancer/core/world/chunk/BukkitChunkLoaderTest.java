package de.raindancer.core.world.chunk;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A chunk loaded "for a moment" has to still be loaded a moment later.
 *
 * <p>Found on a live server: {@code getChunkAtAsync} loads a chunk without anything keeping it, Paper let
 * it go again before the safety search read it, and every block read as "not loaded" — so {@code /dim end}
 * and {@code /dim nether} reported "nowhere safe" standing next to solid ground.
 */
class BukkitChunkLoaderTest {

    private final Plugin plugin = mock(Plugin.class);
    private final World world = mock(World.class);
    private final RegionScheduler regions = mock(RegionScheduler.class);
    private final List<Consumer<ScheduledTask>> releases = new ArrayList<>();
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        bukkit = org.mockito.Mockito.mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getWorld("farm")).thenReturn(world);
        bukkit.when(Bukkit::getRegionScheduler).thenReturn(regions);
        when(world.getChunkAtAsync(anyInt(), anyInt(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(mock(Chunk.class)));
        when(regions.runDelayed(eq(plugin), eq(world), anyInt(), anyInt(), any(), anyLong()))
                .thenAnswer(invocation -> {
                    releases.add(invocation.getArgument(4));
                    return mock(ScheduledTask.class);
                });
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    @DisplayName("a loaded chunk is held by a ticket, and the ticket is let go a moment later")
    void heldForAMoment() {
        BukkitChunkLoader loader = new BukkitChunkLoader(plugin);

        assertThat(loader.load(new ChunkAt("farm", 3, -2)).join()).isTrue();

        verify(world).addPluginChunkTicket(3, -2, plugin);
        verify(world, never()).removePluginChunkTicket(anyInt(), anyInt(), any());
        assertThat(releases).hasSize(1);

        releases.getFirst().accept(mock(ScheduledTask.class));

        verify(world).removePluginChunkTicket(3, -2, plugin);
    }

    @Test
    @DisplayName("two searches over one chunk: the first one finishing does not take the chunk from the second")
    void countedPerChunk() {
        BukkitChunkLoader loader = new BukkitChunkLoader(plugin);
        ChunkAt chunk = new ChunkAt("farm", 0, 0);

        loader.load(chunk).join();
        loader.load(chunk).join();
        releases.getFirst().accept(mock(ScheduledTask.class));

        verify(world, never()).removePluginChunkTicket(anyInt(), anyInt(), any());

        releases.get(1).accept(mock(ScheduledTask.class));

        verify(world, times(1)).removePluginChunkTicket(0, 0, plugin);
    }

    @Test
    @DisplayName("a chunk that failed to load takes no ticket")
    void nothingToHold() {
        when(world.getChunkAtAsync(anyInt(), anyInt(), anyBoolean()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("disk")));

        assertThat(new BukkitChunkLoader(plugin).load(new ChunkAt("farm", 1, 1)).join()).isFalse();

        verify(world, never()).addPluginChunkTicket(anyInt(), anyInt(), any());
    }
}
