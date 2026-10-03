package de.raindancer.core.world.visual;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A task bound to a player is retired with their entity — a logout, a respawn — and never runs again
 * to notice. Whatever remembered it has to be told through the retired callback.
 */
class RetiredTimersTest {

    private final AtomicReference<Runnable> retired = new AtomicReference<>();

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        when(scheduler.runAtFixedRate(any(), any(), any(), anyLong(), anyLong())).thenAnswer(call -> {
            retired.set(call.getArgument(2));
            return mock(ScheduledTask.class);
        });
        return player;
    }

    @Test
    @DisplayName("a navigation whose player was retired is no longer reported as running")
    void navigationIsForgotten() {
        Player player = player();
        Navigator navigator = new Navigator(mock(Plugin.class), null, null);
        navigator.navigate(player, mock(Location.class), "test", "there", null);
        assertThat(navigator.isNavigating(player.getUniqueId())).isTrue();

        assertThat(retired.get()).as("no retired callback was handed over").isNotNull();
        retired.get().run();

        assertThat(navigator.isNavigating(player.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("an outline whose player was retired lets go of them")
    void outlineIsForgotten() throws Exception {
        Player player = player();
        OutlineRenderer outlines = new OutlineRenderer(mock(Plugin.class));
        outlines.showLive(player, mock(World.class), List::of, () -> 64, null);

        assertThat(retired.get()).as("no retired callback was handed over").isNotNull();
        retired.get().run();

        var field = OutlineRenderer.class.getDeclaredField("live");
        field.setAccessible(true);
        assertThat((java.util.Map<?, ?>) field.get(outlines)).isEmpty();
    }
}
