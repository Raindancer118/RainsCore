package de.raindancer.core.moderation.invsee;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An offline edit that is not written — its owner logged in, or the file could not be written — leaves
 * the file exactly as it was. Whatever the moderator moved in either direction has to be undone on
 * their side too, or the window is a way to copy items.
 */
class InventoryWindowTest {

    private static ItemStack stack() {
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(mock(Material.class));
        return item;
    }

    @Test
    @DisplayName("what the moderator took out is taken back from them, not left with both")
    void takenItemsAreTakenBack() {
        ItemStack sword = stack();
        ItemStack added = stack();
        Carried<ItemStack> asFound = Carried.<ItemStack>empty().with(Section.STORAGE, 0, sword);

        Player watcher = mock(Player.class);
        when(watcher.getUniqueId()).thenReturn(UUID.randomUUID());
        PlayerInventory own = mock(PlayerInventory.class);
        when(watcher.getInventory()).thenReturn(own);
        when(own.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
        when(own.removeItem(any(ItemStack[].class))).thenReturn(new HashMap<>());

        Inventory shown = mock(Inventory.class);
        // The sword was taken out of the first storage slot, something was put in the second.
        when(shown.getItem(anyInt())).thenReturn(null);
        when(shown.getItem(Layout.slotFor(Section.STORAGE, 1))).thenReturn(added);

        InventoryWindow window = new InventoryWindow(null, watcher, UUID.randomUUID(), "Owner",
                Access.EDIT, false, null, asFound);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(),
                    any(Component.class))).thenReturn(shown);
            window.getInventory();
            window.sync();
        }
        assertThat(window.carried().at(Section.STORAGE, 0)).isNull();

        window.undoUnwritten();

        // Given back first, then taken back: a slot that was only partly emptied gives the rest
        // of the stack back before the whole original is taken, so the arithmetic comes out even.
        InOrder order = inOrder(own);
        order.verify(own).addItem(added);
        order.verify(own).removeItem(sword);
    }

    @Test
    @DisplayName("a window nobody changed gives and takes nothing")
    void untouchedChangesNothing() {
        ItemStack sword = stack();
        Carried<ItemStack> asFound = Carried.<ItemStack>empty().with(Section.STORAGE, 0, sword);
        Player watcher = mock(Player.class);
        PlayerInventory own = mock(PlayerInventory.class);
        when(watcher.getInventory()).thenReturn(own);

        new InventoryWindow(null, watcher, UUID.randomUUID(), "Owner", Access.EDIT, false, null,
                asFound).undoUnwritten();

        verify(own, never()).addItem(any(ItemStack[].class));
        verify(own, never()).removeItem(any(ItemStack[].class));
    }
}
