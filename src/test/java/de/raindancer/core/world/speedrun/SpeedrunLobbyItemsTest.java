package de.raindancer.core.world.speedrun;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Recognition only — {@link SpeedrunLobbyItems#menuCompass()} and {@code startBlock()} need a live
 * server's item factory to build a real {@code ItemMeta}, the same reason {@code ItemFactory} itself
 * has no unit test of its item-building half. What can be tested without one is the PDC-key
 * recognition, which is the whole point of tagging by key rather than by material or name.
 */
class SpeedrunLobbyItemsTest {

    private Plugin plugin;
    private SpeedrunLobbyItems items;
    private NamespacedKey marker;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("RainsCore");
        when(plugin.namespace()).thenReturn("rainscore");
        marker = new NamespacedKey(plugin, "speedrun-lobby-item");
        items = new SpeedrunLobbyItems(plugin);
    }

    private ItemStack taggedWith(String tag) {
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.get(marker, PersistentDataType.STRING)).thenReturn(tag);
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        ItemStack stack = mock(ItemStack.class);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        return stack;
    }

    @Test
    @DisplayName("a stack tagged 'menu' is recognised as the menu compass")
    void recognisesMenuTag() {
        ItemStack stack = taggedWith("menu");

        assertThat(items.isMenu(stack)).isTrue();
        assertThat(items.isStart(stack)).isFalse();
    }

    @Test
    @DisplayName("a stack tagged 'start' is recognised as the start block")
    void recognisesStartTag() {
        ItemStack stack = taggedWith("start");

        assertThat(items.isStart(stack)).isTrue();
        assertThat(items.isMenu(stack)).isFalse();
    }

    @Test
    @DisplayName("a stack with no meta at all is neither")
    void noMetaIsNeither() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.hasItemMeta()).thenReturn(false);

        assertThat(items.isMenu(stack)).isFalse();
        assertThat(items.isStart(stack)).isFalse();
    }

    @Test
    @DisplayName("a stack tagged for something else entirely is neither")
    void unrelatedTagIsNeither() {
        ItemStack stack = taggedWith("something-else");

        assertThat(items.isMenu(stack)).isFalse();
        assertThat(items.isStart(stack)).isFalse();
    }

    @Test
    @DisplayName("null is neither, without throwing")
    void nullIsNeither() {
        assertThat(items.isMenu(null)).isFalse();
        assertThat(items.isStart(null)).isFalse();
    }
}
