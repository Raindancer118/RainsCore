package de.raindancer.core.content.items;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A spawn egg sold by a shop hatches a mob, but never turns a spawner into a farm of that mob. */
@DisplayName("an egg marked not for spawners is refused by spawners, and only by them")
class NotForSpawnersTest {

    private final List<Player> told = new ArrayList<>();
    private final NotForSpawnersListener listener = new NotForSpawnersListener(told::add);

    private static ItemStack egg(boolean marked) {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(stack.getType()).thenReturn(Material.ZOMBIE_SPAWN_EGG);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.has(NotForSpawners.KEY, PersistentDataType.BYTE)).thenReturn(marked);
        return stack;
    }

    private static PlayerInteractEvent click(Action action, Material block, ItemStack item) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        Block clicked = mock(Block.class);
        when(clicked.getType()).thenReturn(block);
        when(event.getAction()).thenReturn(action);
        when(event.getClickedBlock()).thenReturn(block == null ? null : clicked);
        when(event.getItem()).thenReturn(item);
        when(event.getPlayer()).thenReturn(mock(Player.class));
        return event;
    }

    @Test
    @DisplayName("a marked egg is recognised, and nothing else is")
    void recognised() {
        assertThat(NotForSpawners.isMarked(egg(true))).isTrue();
        assertThat(NotForSpawners.isMarked(egg(false))).isFalse();
        assertThat(NotForSpawners.isMarked(null)).isFalse();
    }

    @Test
    @DisplayName("a marked egg on a spawner or a trial spawner is refused, and the player is told")
    void refused() {
        for (Material spawner : List.of(Material.SPAWNER, Material.TRIAL_SPAWNER)) {
            PlayerInteractEvent event = click(Action.RIGHT_CLICK_BLOCK, spawner, egg(true));
            listener.onUse(event);
            verify(event).setCancelled(true);
        }
        assertThat(told).hasSize(2);
    }

    @Test
    @DisplayName("an ordinary egg on a spawner, a marked egg on the ground, and a left click are left alone")
    void leftAlone() {
        PlayerInteractEvent plain = click(Action.RIGHT_CLICK_BLOCK, Material.SPAWNER, egg(false));
        PlayerInteractEvent ground = click(Action.RIGHT_CLICK_BLOCK, Material.GRASS_BLOCK, egg(true));
        PlayerInteractEvent hit = click(Action.LEFT_CLICK_BLOCK, Material.SPAWNER, egg(true));
        for (PlayerInteractEvent event : List.of(plain, ground, hit)) {
            listener.onUse(event);
            verify(event, never()).setCancelled(any(Boolean.class));
        }
        assertThat(told).isEmpty();
    }
}
