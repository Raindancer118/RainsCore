package de.raindancer.core.content.items;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Material;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A warp token is a nether star, and a nether star is half a beacon. An item marked as no ingredient
 * must not be crafted away as the vanilla block it is made of.
 */
class NotAnIngredientTest {

    private CustomItems items;
    private ItemFactory factory;
    private CustomItemListener listener;
    private ItemStack token;
    private ItemStack plain;
    private CraftingInventory grid;

    @BeforeEach
    void setUp() {
        items = new CustomItems(Path.of("target", "no-such-items.yml"));
        items.define(CustomItem.builder("warps", "token").material(Material.NETHER_STAR)
                .notAnIngredient().build());
        items.define(CustomItem.builder("warps", "trinket").material(Material.NETHER_STAR).build());

        factory = mock(ItemFactory.class);
        token = mock(ItemStack.class);
        plain = mock(ItemStack.class);
        when(factory.keyOf(same(token))).thenReturn(Optional.of("warps:token"));
        when(factory.keyOf(same(plain))).thenReturn(Optional.empty());
        grid = mock(CraftingInventory.class);
        listener = new CustomItemListener(items, factory, new ItemAbilities(() -> 0L), mock(Messages.class));
    }

    private PrepareItemCraftEvent crafting(ItemStack... matrix) {
        when(grid.getMatrix()).thenReturn(matrix);
        PrepareItemCraftEvent event = mock(PrepareItemCraftEvent.class);
        when(event.getInventory()).thenReturn(grid);
        return event;
    }

    @Test
    @DisplayName("a token in the grid makes nothing")
    void tokenMakesNothing() {
        listener.onPrepareCraft(crafting(plain, token, null));

        verify(grid).setResult(null);
    }

    @Test
    @DisplayName("ordinary ingredients, and custom items not marked, craft as they always did")
    void othersUntouched() {
        ItemStack trinket = mock(ItemStack.class);
        when(factory.keyOf(same(trinket))).thenReturn(Optional.of("warps:trinket"));

        listener.onPrepareCraft(crafting(plain, trinket, null));

        verify(grid, never()).setResult(any());
    }

    @Test
    @DisplayName("the marker is a tag, so it survives being saved and read back like any other")
    void isATag() {
        org.assertj.core.api.Assertions.assertThat(items.byKey("warps:token").orElseThrow().isIngredient()).isFalse();
        org.assertj.core.api.Assertions.assertThat(items.byKey("warps:trinket").orElseThrow().isIngredient()).isTrue();
    }
}
