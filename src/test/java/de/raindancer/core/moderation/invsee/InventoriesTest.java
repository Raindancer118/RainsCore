package de.raindancer.core.moderation.invsee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
}
