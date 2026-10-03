package de.raindancer.core.world.safety;

import de.raindancer.core.world.chunk.ChunkAt;
import de.raindancer.core.world.chunk.ChunkHolds;
import de.raindancer.core.world.chunk.ChunkLoader;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Blocks are read on the thread that owns them. On Folia that is the region around the search, not
 * the global region — which owns no chunks at all, so reading there throws.
 */
class SafetyThreadTest {

    @Test
    @DisplayName("a search finished off-thread reads its blocks on the region that owns the spot")
    void readsOnTheOwningRegion() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        ChunkLoader loaded = new ChunkLoader() {
            @Override
            public boolean isLoaded(ChunkAt chunk) {
                return true;
            }

            @Override
            public CompletableFuture<Boolean> load(ChunkAt chunk) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public void keepLoaded(ChunkAt chunk, boolean keep) {
            }
        };
        Safety safety = new Safety(plugin, new ChunkHolds(loaded), world -> null);
        World world = mock(World.class);
        RegionScheduler regions = mock(RegionScheduler.class);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        List<Runnable> onRegion = new ArrayList<>();
        org.mockito.Mockito.doAnswer(call -> {
            onRegion.add(call.getArgument(2));
            return null;
        }).when(regions).execute(any(), any(Location.class), any(Runnable.class));

        CompletableFuture<Optional<Spot>> found;
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("farm")).thenReturn(world);
            bukkit.when(Bukkit::getRegionScheduler).thenReturn(regions);
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(World.class), anyInt(), anyInt()))
                    .thenReturn(false);

            found = safety.findSafe(new Spot("farm", 100, 64, 100), 4);
        }

        verifyNoInteractions(global);
        assertThat(onRegion).hasSize(1);
        assertThat(found).isNotDone();
        onRegion.getFirst().run();
        assertThat(found).isCompletedWithValue(Optional.empty());
    }
}
