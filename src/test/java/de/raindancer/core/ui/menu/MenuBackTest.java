package de.raindancer.core.ui.menu;

import de.raindancer.core.ui.chat.Brand;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MenuBackTest {

    private static final class Page extends Menu {

        private Page(Player viewer) {
            super(viewer, new Brand("Test"), null);
        }

        @Override
        protected Component title() {
            return Component.text("Page");
        }

        @Override
        protected void render() {
        }
    }

    private static Player lookingAt(InventoryHolder holder) {
        Player viewer = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        when(viewer.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(top);
        when(top.getHolder(false)).thenReturn(holder);
        when(top.getHolder()).thenReturn(holder);
        return viewer;
    }

    @Test
    @DisplayName("a chooser whose answer opened the next page leaves that page open")
    void answerOpenedAnotherPage() {
        Player viewer = lookingAt(mock(InventoryHolder.class));
        new Page(viewer).backToWhoeverOpenedThis();
        verify(viewer, never()).closeInventory();
    }

    @Test
    @DisplayName("a chooser still on screen after its answer, with nowhere to go back to, closes")
    void stillOnScreenCloses() {
        Player viewer = mock(Player.class);
        Page page = new Page(viewer);
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        when(viewer.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(top);
        when(top.getHolder(false)).thenReturn(page);
        when(top.getHolder()).thenReturn(page);
        page.backToWhoeverOpenedThis();
        verify(viewer).closeInventory();
    }
}
