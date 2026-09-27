package de.raindancer.core.content.items;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Items a plugin hands out as buttons, which a player may not throw away. */
@DisplayName("bound items")
class BoundItemsTest {

    private static ItemStack stack(boolean bound) {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.has(BoundItems.KEY, PersistentDataType.BYTE)).thenReturn(bound);
        return stack;
    }

    private static PlayerDropItemEvent dropping(ItemStack stack) {
        Item entity = mock(Item.class);
        when(entity.getItemStack()).thenReturn(stack);
        return new PlayerDropItemEvent(mock(Player.class), entity);
    }

    @Test
    @DisplayName("the key is Core's own, so every plugin's bound items are recognised by the one listener")
    void keyIsCores() {
        assertThat(BoundItems.KEY.getNamespace()).isEqualTo("rainscore");
        assertThat(BoundItems.KEY.getKey()).isEqualTo("bound");
    }

    @Test
    @DisplayName("binding writes the marker into the item's own data")
    void bindWrites() {
        ItemStack stack = stack(false);
        ItemMeta meta = stack.getItemMeta();

        assertThat(BoundItems.bind(stack)).isSameAs(stack);

        verify(meta.getPersistentDataContainer()).set(BoundItems.KEY, PersistentDataType.BYTE, (byte) 1);
        verify(stack).setItemMeta(meta);
    }

    @Test
    @DisplayName("recognises a bound item, and nothing else — null, air, or an item with no data")
    void recognises() {
        assertThat(BoundItems.isBound(stack(true))).isTrue();
        assertThat(BoundItems.isBound(stack(false))).isFalse();
        assertThat(BoundItems.isBound(null)).isFalse();
        ItemStack plain = mock(ItemStack.class);
        when(plain.hasItemMeta()).thenReturn(false);
        assertThat(BoundItems.isBound(plain)).isFalse();
    }

    @Test
    @DisplayName("a bound item cannot be dropped")
    void dropRefused() {
        PlayerDropItemEvent event = dropping(stack(true));

        new BoundItemListener().onDrop(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("anything else drops as usual")
    void dropAllowed() {
        PlayerDropItemEvent event = dropping(stack(false));

        new BoundItemListener().onDrop(event);

        assertThat(event.isCancelled()).isFalse();
    }
}
