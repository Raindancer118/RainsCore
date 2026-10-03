package de.raindancer.core.moderation.invsee;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The four settings the "Looking Inside" config page has had for as long as it has existed, none of
 * which used to do anything: {@code Inventories} never asked, so switching any of them in the running
 * config left {@code /invsee} behaving exactly as before.
 */
class InventoriesTest {

    private Inventories inventories() {
        return new Inventories(null, new InventoryViews(name -> { }),
                new OfflineEdits(System::currentTimeMillis), null, null, who -> false);
    }

    @Nested
    @DisplayName("switched off entirely")
    class SwitchedOff {

        @Test
        @DisplayName("open() refuses at once, before anything else is even asked")
        void refusesBeforeTouchingAnythingElse() {
            Inventories inventories = inventories();
            inventories.enabled(false);
            AtomicReference<Inventories.Outcome> got = new AtomicReference<>();

            // Null watcher and owner: if this reached any check past "are we even switched on", it
            // would throw rather than answer — proving the gate really is first.
            inventories.open(null, null, null, null, got::set);

            assertThat(got.get()).isEqualTo(Inventories.Outcome.SWITCHED_OFF);
        }

        @Test
        @DisplayName("is on by default, so an untouched server behaves exactly as it always has")
        void onByDefault() {
            assertThat(inventories().isEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("capping what was asked for to what the server allows")
    class Capping {

        @Test
        @DisplayName("nothing is capped by default")
        void defaultsChangeNothing() {
            Inventories inventories = inventories();

            assertThat(inventories.capToSettings(Access.READ_ONLY)).isEqualTo(Access.READ_ONLY);
            assertThat(inventories.capToSettings(Access.EDIT)).isEqualTo(Access.EDIT);
            assertThat(inventories.capToSettings(Access.EDIT_EVERYTHING))
                    .isEqualTo(Access.EDIT_EVERYTHING);
        }

        @Test
        @DisplayName("editing switched off caps everything down to looking, whatever was asked")
        void editingOffCapsToReadOnly() {
            Inventories inventories = inventories();
            inventories.allowEditing(false);

            assertThat(inventories.capToSettings(Access.EDIT)).isEqualTo(Access.READ_ONLY);
            assertThat(inventories.capToSettings(Access.EDIT_EVERYTHING)).isEqualTo(Access.READ_ONLY);
        }

        @Test
        @DisplayName("equipment switched off caps EDIT_EVERYTHING down to EDIT, not further")
        void equipmentOffCapsToPlainEdit() {
            Inventories inventories = inventories();
            inventories.allowEquipment(false);

            assertThat(inventories.capToSettings(Access.EDIT_EVERYTHING)).isEqualTo(Access.EDIT);
            assertThat(inventories.capToSettings(Access.EDIT))
                    .as("plain editing was never asking for equipment, so this setting has no say")
                    .isEqualTo(Access.EDIT);
            assertThat(inventories.capToSettings(Access.READ_ONLY)).isEqualTo(Access.READ_ONLY);
        }

        @Test
        @DisplayName("editing off wins over equipment off — there is nothing left to further restrict")
        void editingOffTakesPriority() {
            Inventories inventories = inventories();
            inventories.allowEditing(false);
            inventories.allowEquipment(false);

            assertThat(inventories.capToSettings(Access.EDIT_EVERYTHING)).isEqualTo(Access.READ_ONLY);
        }
    }

    @Nested
    @DisplayName("a moderator who disconnects with an offline window open")
    class EditorDisconnects {

        @Test
        @DisplayName("still has their edit written: the close comes before the quit, and the quit must "
                + "not let go of a hold whose write is already on its way")
        @SuppressWarnings("unchecked")
        void theClosingWriteSurvivesTheQuit() {
            UUID owner = UUID.randomUUID();
            UUID moderator = UUID.randomUUID();
            OfflineEdits edits = new OfflineEdits(System::currentTimeMillis);
            assertThat(edits.begin(owner, moderator)).isTrue();
            PlayerDataInventorySource saved = mock(PlayerDataInventorySource.class);
            Carried<ItemStack> carried = Carried.empty();
            when(saved.write(eq(owner), any())).thenReturn(true);
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            Inventories inventories = new Inventories(plugin,
                    new InventoryViews(name -> { }), edits, null, saved, who -> false);

            Player watcher = mock(Player.class);
            when(watcher.getUniqueId()).thenReturn(moderator);
            InventoryWindow window = mock(InventoryWindow.class);
            when(window.watcher()).thenReturn(watcher);
            when(window.owner()).thenReturn(owner);
            when(window.isLive()).thenReturn(false);
            when(window.access()).thenReturn(Access.EDIT);
            when(window.carried()).thenReturn(carried);

            List<Consumer<ScheduledTask>> queued = new ArrayList<>();
            AsyncScheduler async = mock(AsyncScheduler.class);
            when(async.runNow(any(), any())).thenAnswer(call -> {
                queued.add(call.getArgument(1));
                return null;
            });
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getAsyncScheduler).thenReturn(async);

                // Paper's order on a disconnect: the open window is closed, then PlayerQuitEvent.
                inventories.closed(window);
                inventories.editorLeft(moderator);
                queued.forEach(task -> task.accept(null));
            }

            verify(saved).write(owner, carried);
            assertThat(edits.isBeingEdited(owner)).as("the write let the hold go").isFalse();
        }

        @Test
        @DisplayName("a hold with no window closing behind it is still let go")
        void anIdleHoldIsReleased() {
            UUID owner = UUID.randomUUID();
            UUID moderator = UUID.randomUUID();
            OfflineEdits edits = new OfflineEdits(System::currentTimeMillis);
            edits.begin(owner, moderator);
            Inventories inventories = new Inventories(null, new InventoryViews(name -> { }), edits,
                    null, null, who -> false);

            assertThat(inventories.editorLeft(moderator)).containsExactly(owner);
            assertThat(edits.isBeingEdited(owner)).isFalse();
        }
    }

    @Test
    @DisplayName("a window closed while the plugin is shutting down is written there and then")
    void closedDuringShutdownIsWritten() {
        UUID owner = UUID.randomUUID();
        UUID moderator = UUID.randomUUID();
        OfflineEdits edits = new OfflineEdits(System::currentTimeMillis);
        edits.begin(owner, moderator);
        PlayerDataInventorySource saved = mock(PlayerDataInventorySource.class);
        Carried<ItemStack> carried = Carried.empty();
        when(saved.write(eq(owner), any())).thenReturn(true);
        Plugin disabling = mock(Plugin.class);
        Inventories inventories = new Inventories(disabling, new InventoryViews(name -> { }), edits,
                null, saved, who -> false);
        Player watcher = mock(Player.class);
        when(watcher.getUniqueId()).thenReturn(moderator);
        InventoryWindow window = mock(InventoryWindow.class);
        when(window.watcher()).thenReturn(watcher);
        when(window.owner()).thenReturn(owner);
        when(window.access()).thenReturn(Access.EDIT);
        when(window.carried()).thenReturn(carried);

        // No scheduler is mocked at all: a disabled plugin's task runs in place.
        inventories.closed(window);

        verify(saved).write(owner, carried);
    }
}
