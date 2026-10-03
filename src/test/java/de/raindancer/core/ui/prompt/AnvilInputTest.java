package de.raindancer.core.ui.prompt;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** "Type a value" in an anvil: read as typed, taken only when it is an answer, never giving anything away. */
class AnvilInputTest {

    private final AtomicReference<String> answered = new AtomicReference<>();
    private final AtomicInteger cancelled = new AtomicInteger();

    private AnvilInput.Session<String> session() {
        return new AnvilInput.Session<>(Parsers.name(8), answered::set, cancelled::incrementAndGet);
    }

    @Test
    @DisplayName("the result slot shows the answer, or what is wrong with it, while typing")
    void shown() {
        AnvilInput.Session<String> session = session();

        assertThat(session.show("base")).isEqualTo(new AnvilInput.Shown(true, "base", "Click to use this"));
        assertThat(session.show("my base").usable()).isFalse();
        assertThat(session.show("my base").line()).contains("letters, digits");
    }

    @Test
    @DisplayName("taken only when it is an answer, and only once; closing afterwards is not a cancel")
    void takeOnce() {
        AnvilInput.Session<String> session = session();

        assertThat(session.take("no spaces")).isFalse();
        assertThat(session.take("base")).isTrue();
        assertThat(session.take("other")).isFalse();
        session.closed();

        assertThat(answered).hasValue("base");
        assertThat(cancelled).hasValue(0);
    }

    @Test
    @DisplayName("in the window: every click is refused, the result answers and shuts it, and nothing is handed back")
    void listener() {
        UUID id = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        AnvilView view = mock(AnvilView.class);
        AnvilInventory top = mock(AnvilInventory.class);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getRenameText()).thenReturn("base");
        AnvilInput.start(id, session());

        InventoryClickEvent elsewhere = mock(InventoryClickEvent.class);
        when(elsewhere.getWhoClicked()).thenReturn(player);
        when(elsewhere.getView()).thenReturn(view);
        when(elsewhere.getRawSlot()).thenReturn(0);
        new AnvilInput.Listener().onClick(elsewhere);
        verify(elsewhere).setCancelled(true);
        assertThat(answered).hasValue(null);

        InventoryClickEvent result = mock(InventoryClickEvent.class);
        when(result.getWhoClicked()).thenReturn(player);
        when(result.getView()).thenReturn(view);
        when(result.getRawSlot()).thenReturn(2);
        new AnvilInput.Listener().onClick(result);

        assertThat(answered).hasValue("base");
        verify(top).setItem(0, null);
        verify(player).closeInventory();
        assertThat(AnvilInput.isAsking(id)).isFalse();
    }

    @Test
    @DisplayName("closed without an answer is a cancel, and the paper in it goes back to nobody")
    void closedIsCancel() {
        UUID id = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        AnvilView view = mock(AnvilView.class);
        AnvilInventory top = mock(AnvilInventory.class);
        when(view.getTopInventory()).thenReturn(top);
        AnvilInput.start(id, session());
        InventoryCloseEvent close = mock(InventoryCloseEvent.class);
        when(close.getPlayer()).thenReturn(player);
        when(close.getView()).thenReturn(view);

        new AnvilInput.Listener().onClose(close);

        assertThat(cancelled).hasValue(1);
        verify(top).setItem(0, null);
        verify(player, never()).closeInventory();
    }
}
