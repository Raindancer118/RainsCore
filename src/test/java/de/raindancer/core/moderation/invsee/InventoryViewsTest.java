package de.raindancer.core.moderation.invsee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Looking inside somebody else's inventory.
 *
 * <h2>Why the rules are the whole feature</h2>
 * Opening another player's inventory is three lines. Everything that makes it usable is the rules
 * around it, and every one of them is something that has gone wrong on a real server:
 *
 * <ul>
 *   <li>Two moderators with the same inventory open, both dragging — items duplicated.</li>
 *   <li>Somebody watching an inventory when its owner logs out — the window keeps working and
 *       changes are written to a player who is not there, or lost.</li>
 *   <li>A read-only view that is not actually read-only, because only some of the ways to move an
 *       item were blocked.</li>
 *   <li>Armour and the off-hand shown as ordinary slots, so a moderator can accidentally unequip
 *       somebody by clicking.</li>
 * </ul>
 *
 * <p>So who is watching whom, and what they may do, is bookkeeping — and bookkeeping is testable.
 */
@DisplayName("inventory views")
class InventoryViewsTest {

    private final List<String> closed = new ArrayList<>();

    private InventoryViews views() {
        return new InventoryViews(closed::add);
    }

    private static final UUID MOD = UUID.randomUUID();
    private static final UUID OTHER_MOD = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    // ------------------------------------------------------------------ opening

    @Nested
    @DisplayName("opening one")
    class Opening {

        @Test
        @DisplayName("a moderator can watch somebody")
        void opens() {
            InventoryViews views = views();
            assertThat(views.open(MOD, TARGET, Access.READ_ONLY)).isTrue();

            assertThat(views.watching(MOD)).contains(TARGET);
            assertThat(views.watchersOf(TARGET)).containsExactly(MOD);
            assertThat(views.accessOf(MOD)).contains(Access.READ_ONLY);
        }

        @Test
        @DisplayName("nobody can watch themselves through this")
        void notYourself() {
            assertThat(views().open(MOD, MOD, Access.READ_ONLY))
                    .as("your own inventory is a key, not a menu, and the two behave differently")
                    .isFalse();
        }

        @Test
        @DisplayName("opening a second one closes the first")
        void oneAtATime() {
            InventoryViews views = views();
            UUID second = UUID.randomUUID();
            views.open(MOD, TARGET, Access.READ_ONLY);
            views.open(MOD, second, Access.READ_ONLY);

            assertThat(views.watching(MOD))
                    .as("one screen, one inventory; anything else is a window nobody is looking at "
                            + "still taking clicks")
                    .contains(second);
            assertThat(views.watchersOf(TARGET)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ two at once

    /**
     * The one that actually duplicates items. Two people dragging in the same inventory is a
     * duplication bug in every plugin that has ever allowed it.
     */
    @Nested
    @DisplayName("two people at once")
    class Sharing {

        @Test
        @DisplayName("two can look at the same inventory")
        void twoWatchers() {
            InventoryViews views = views();
            views.open(MOD, TARGET, Access.READ_ONLY);

            assertThat(views.open(OTHER_MOD, TARGET, Access.READ_ONLY)).isTrue();
            assertThat(views.watchersOf(TARGET)).containsExactlyInAnyOrder(MOD, OTHER_MOD);
        }

        @Test
        @DisplayName("only one of them can be editing")
        void oneEditorOnly() {
            InventoryViews views = views();
            views.open(MOD, TARGET, Access.EDIT);

            assertThat(views.open(OTHER_MOD, TARGET, Access.EDIT))
                    .as("two people dragging in the same inventory duplicates items, every time")
                    .isFalse();
            assertThat(views.open(OTHER_MOD, TARGET, Access.READ_ONLY))
                    .as("watching while somebody else edits is fine")
                    .isTrue();
        }

        @Test
        @DisplayName("re-opening the inventory you are editing does not drop your lock")
        void reopeningKeepsTheLock() {
            InventoryViews views = views();
            views.open(MOD, TARGET, Access.EDIT);

            // A moderator refreshing the window, or opening it again from a menu. Found by review:
            // the lock was taken (it was already theirs) and then released on the way past, so
            // nobody held it and the next moderator was let straight in — both editing at once,
            // which is the item-duplication case this class exists to stop.
            views.open(MOD, TARGET, Access.EDIT);

            assertThat(views.editorOf(TARGET)).contains(MOD);
            assertThat(views.open(OTHER_MOD, TARGET, Access.EDIT))
                    .as("somebody else must still be refused")
                    .isFalse();
        }

        @Test
        @DisplayName("the editor letting go lets somebody else edit")
        void editingCanBeHandedOver() {
            InventoryViews views = views();
            views.open(MOD, TARGET, Access.EDIT);
            views.close(MOD);

            assertThat(views.open(OTHER_MOD, TARGET, Access.EDIT)).isTrue();
        }

        @Test
        @DisplayName("who is editing can be asked, so a watcher can be told why they cannot")
        void saysWhoIsEditing() {
            InventoryViews views = views();
            views.open(MOD, TARGET, Access.EDIT);
            assertThat(views.editorOf(TARGET)).contains(MOD);
        }
    }

    // ------------------------------------------------------------------ what may be touched

    @Nested
    @DisplayName("what a watcher may touch")
    class Permissions {

        @Test
        @DisplayName("a read-only watcher may not change anything")
        void readOnlyIsReadOnly() {
            InventoryViews views = views();
            views.open(MOD, TARGET, Access.READ_ONLY);

            assertThat(views.mayChange(MOD, Section.STORAGE)).isFalse();
            assertThat(views.mayChange(MOD, Section.HOTBAR)).isFalse();
            assertThat(views.mayChange(MOD, Section.ENDER_CHEST)).isFalse();
        }

        @Test
        @DisplayName("an editor may change what they are carrying")
        void editorsMayEdit() {
            InventoryViews views = views();
            views.open(MOD, TARGET, Access.EDIT);

            assertThat(views.mayChange(MOD, Section.STORAGE)).isTrue();
            assertThat(views.mayChange(MOD, Section.HOTBAR)).isTrue();
            assertThat(views.mayChange(MOD, Section.ENDER_CHEST)).isTrue();
        }

        @Test
        @DisplayName("armour and the off-hand are protected unless asked for")
        void equipmentIsProtected() {
            InventoryViews views = views();
            views.open(MOD, TARGET, Access.EDIT);

            assertThat(views.mayChange(MOD, Section.ARMOUR))
                    .as("unequipping somebody by accident, mid-fight, from a click meant for the "
                            + "slot next to it")
                    .isFalse();
            assertThat(views.mayChange(MOD, Section.OFF_HAND)).isFalse();

            views.open(MOD, TARGET, Access.EDIT_EVERYTHING);
            assertThat(views.mayChange(MOD, Section.ARMOUR)).isTrue();
            assertThat(views.mayChange(MOD, Section.OFF_HAND)).isTrue();
        }

        @Test
        @DisplayName("somebody who is not watching anything may not change anything")
        void strangersMayNot() {
            assertThat(views().mayChange(MOD, Section.STORAGE)).isFalse();
        }
    }

    // ------------------------------------------------------------------ closing

    @Nested
    @DisplayName("closing")
    class Closing {

        @Test
        @DisplayName("a watcher can stop watching")
        void closes() {
            InventoryViews views = views();
            views.open(MOD, TARGET, Access.EDIT);

            assertThat(views.close(MOD)).isTrue();
            assertThat(views.watching(MOD)).isEmpty();
            assertThat(views.watchersOf(TARGET)).isEmpty();
        }

        @Test
        @DisplayName("closing when you are not watching is not an error")
        void closingNothing() {
            assertThat(views().close(MOD)).isFalse();
        }

        @Test
        @DisplayName("the owner logging out closes every window onto them")
        void ownerLeaving() {
            InventoryViews views = views();
            views.open(MOD, TARGET, Access.EDIT);
            views.open(OTHER_MOD, TARGET, Access.READ_ONLY);

            assertThat(views.ownerLeft(TARGET)).containsExactlyInAnyOrder(MOD, OTHER_MOD);
            assertThat(views.watchersOf(TARGET))
                    .as("a window onto somebody who has gone is a window whose changes are "
                            + "written to nobody")
                    .isEmpty();
            assertThat(closed).containsExactlyInAnyOrder(MOD.toString(), OTHER_MOD.toString());
        }

        @Test
        @DisplayName("a watcher logging out closes their own window")
        void watcherLeaving() {
            InventoryViews views = views();
            views.open(MOD, TARGET, Access.EDIT);

            views.watcherLeft(MOD);
            assertThat(views.watchersOf(TARGET)).isEmpty();
            assertThat(views.editorOf(TARGET))
                    .as("an editor who logs out must release the lock, or nobody can ever edit "
                            + "that inventory again until a restart")
                    .isEmpty();
        }

        @Test
        @DisplayName("everything can be closed at once, for a shutdown")
        void closesEverything() {
            InventoryViews views = views();
            views.open(MOD, TARGET, Access.EDIT);
            views.open(OTHER_MOD, UUID.randomUUID(), Access.READ_ONLY);

            assertThat(views.closeEverything()).isEqualTo(2);
            assertThat(views.size()).isZero();
        }
    }

    // ------------------------------------------------------------------ the parts of an inventory

    /**
     * Telling the parts apart, which is what makes the window readable.
     *
     * <p>None of this is guessable and all of it is wrong in most plugins: the hotbar is stored
     * first but drawn last, armour is stored in the reverse of the order it is worn, and the ender
     * chest is not in the inventory at all.
     */
    @Nested
    @DisplayName("the parts of an inventory")
    class Parts {

        @Test
        @DisplayName("the hotbar is the first nine, however oddly that reads")
        void hotbar() {
            assertThat(Slots.sectionOf(0)).contains(Section.HOTBAR);
            assertThat(Slots.sectionOf(8)).contains(Section.HOTBAR);
            assertThat(Slots.indexWithin(3)).isEqualTo(3);
        }

        @Test
        @DisplayName("the backpack is the twenty-seven above it")
        void storage() {
            assertThat(Slots.sectionOf(9)).contains(Section.STORAGE);
            assertThat(Slots.sectionOf(35)).contains(Section.STORAGE);
            assertThat(Slots.indexWithin(9)).isZero();
        }

        @Test
        @DisplayName("armour comes back the way a person would list it, not the way it is stored")
        void armour() {
            assertThat(Slots.sectionOf(39)).contains(Section.ARMOUR);
            assertThat(Slots.indexWithin(39))
                    .as("slot 39 is the helmet even though it is the last of the four; drawing "
                            + "them in array order puts the boots on somebody's head")
                    .isZero();
            assertThat(Slots.indexWithin(36)).isEqualTo(3);
            assertThat(Slots.armourSlot(0)).isEqualTo(39);
            assertThat(Slots.armourSlot(3)).isEqualTo(36);
        }

        @Test
        @DisplayName("the off-hand is on its own")
        void offHand() {
            assertThat(Slots.sectionOf(40)).contains(Section.OFF_HAND);
            assertThat(Slots.indexWithin(40)).isZero();
        }

        @Test
        @DisplayName("a slot that is none of those is nothing, rather than guessed at")
        void nonsenseSlots() {
            assertThat(Slots.sectionOf(-1)).isEmpty();
            assertThat(Slots.sectionOf(99)).isEmpty();
        }

        /**
         * The save file numbers the same slots differently, and nothing warns you.
         *
         * <p>In {@code player.dat} armour is 100 to 103 and the off-hand is −106, where in a running
         * game they are 36 to 39 and 40. Reading a file with the in-game numbers puts a helmet on
         * somebody's feet — and the file is written back, so it is a real helmet on real feet.
         */
        @Test
        @DisplayName("armour in the save file is numbered from a hundred, boots first")
        void fileArmour() {
            assertThat(Slots.sectionOfFileSlot(103)).contains(Section.ARMOUR);
            assertThat(Slots.indexWithinFileSlot(103))
                    .as("103 is the helmet, which this counts as the first piece")
                    .isZero();
            assertThat(Slots.indexWithinFileSlot(102)).isEqualTo(1);
            assertThat(Slots.indexWithinFileSlot(101)).isEqualTo(2);
            assertThat(Slots.indexWithinFileSlot(100))
                    .as("100 is the boots — the opposite end from where a person would start")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("the off-hand in the save file is a negative number")
        void fileOffHand() {
            assertThat(Slots.sectionOfFileSlot(-106)).contains(Section.OFF_HAND);
            assertThat(Slots.indexWithinFileSlot(-106)).isZero();
        }

        @Test
        @DisplayName("the hotbar and backpack are numbered the same in both")
        void fileCarried() {
            assertThat(Slots.sectionOfFileSlot(0)).contains(Section.HOTBAR);
            assertThat(Slots.sectionOfFileSlot(8)).contains(Section.HOTBAR);
            assertThat(Slots.sectionOfFileSlot(9)).contains(Section.STORAGE);
            assertThat(Slots.sectionOfFileSlot(35)).contains(Section.STORAGE);
            assertThat(Slots.indexWithinFileSlot(35)).isEqualTo(26);
        }

        @Test
        @DisplayName("a slot number the file should not contain is nothing, not a guess")
        void fileNonsense() {
            assertThat(Slots.sectionOfFileSlot(36))
                    .as("36 is armour in a running game and nothing in the file: rounding it to "
                            + "the nearest real part is how an item moves by itself")
                    .isEmpty();
            assertThat(Slots.sectionOfFileSlot(99)).isEmpty();
            assertThat(Slots.sectionOfFileSlot(104)).isEmpty();
            assertThat(Slots.sectionOfFileSlot(-1)).isEmpty();
            assertThat(Slots.indexWithinFileSlot(36)).isEqualTo(-1);
        }

        @Test
        @DisplayName("every part of the file numbering comes back the way it went in")
        void fileRoundTrips() {
            for (Section section : List.of(Section.HOTBAR, Section.STORAGE, Section.ARMOUR,
                    Section.OFF_HAND)) {
                for (int within = 0; within < section.size(); within++) {
                    int fileSlot = Slots.fileSlot(section, within);
                    assertThat(Slots.sectionOfFileSlot(fileSlot))
                            .as(section + " position " + within + " landed in the wrong part")
                            .contains(section);
                    assertThat(Slots.indexWithinFileSlot(fileSlot))
                            .as(section + " position " + within + " did not come back the same")
                            .isEqualTo(within);
                }
            }
        }

        @Test
        @DisplayName("the two numberings really are different, which is the whole point")
        void theNumberingsDiffer() {
            assertThat(Slots.fileSlot(Section.ARMOUR, 0)).isEqualTo(103);
            assertThat(Slots.rawSlot(Section.ARMOUR, 0)).isEqualTo(39);
            assertThat(Slots.fileSlot(Section.OFF_HAND, 0)).isEqualTo(-106);
            assertThat(Slots.rawSlot(Section.OFF_HAND, 0)).isEqualTo(40);
        }

        @Test
        @DisplayName("a raw slot can be worked out from a part and a position")
        void roundTrips() {
            for (Section section : List.of(Section.HOTBAR, Section.STORAGE, Section.ARMOUR,
                    Section.OFF_HAND)) {
                for (int within = 0; within < section.size(); within++) {
                    int raw = Slots.rawSlot(section, within);
                    assertThat(Slots.sectionOf(raw)).contains(section);
                    assertThat(Slots.indexWithin(raw))
                            .as(section + " position " + within + " did not come back the same")
                            .isEqualTo(within);
                }
            }
        }

        @Test
        @DisplayName("each part says what to call it and what to draw it with")
        void partsHaveChrome() {
            for (Section section : Section.values()) {
                assertThat(section.title()).isNotBlank();
                assertThat(section.icon()).isNotBlank();
                assertThat(section.size()).isPositive();
            }
        }

        @Test
        @DisplayName("the ones that are worn, and the one that is not in the inventory, are marked")
        void specialParts() {
            assertThat(Section.ARMOUR.isEquipment()).isTrue();
            assertThat(Section.OFF_HAND.isEquipment()).isTrue();
            assertThat(Section.STORAGE.isEquipment()).isFalse();
            assertThat(Section.ENDER_CHEST.isSeparate())
                    .as("read from the inventory array it is simply not there, and a window that "
                            + "assumes otherwise shows an empty box")
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------ where things are drawn

    /**
     * The window arrangement, which is what makes the feature readable rather than merely working.
     *
     * <p>It mirrors a player's own screen — backpack above, hotbar below it, worn armour off to the
     * side — because that is the one arrangement every player can already read without being told.
     */
    @Nested
    @DisplayName("the window layout")
    class WindowLayout {

        @Test
        @DisplayName("the backpack is the top three rows and the hotbar the one under it")
        void carriedThings() {
            assertThat(Layout.at(0)).contains(new Layout.Placed(Section.STORAGE, 0));
            assertThat(Layout.at(26)).contains(new Layout.Placed(Section.STORAGE, 26));
            assertThat(Layout.at(27))
                    .as("the hotbar goes under the backpack, as it does on a player's own screen")
                    .contains(new Layout.Placed(Section.HOTBAR, 0));
            assertThat(Layout.at(35)).contains(new Layout.Placed(Section.HOTBAR, 8));
        }

        @Test
        @DisplayName("worn armour is on the bottom row, helmet first")
        void wornThings() {
            assertThat(Layout.at(Layout.ARMOUR_FIRST))
                    .contains(new Layout.Placed(Section.ARMOUR, 0));
            assertThat(Layout.armourName(0)).isEqualTo("Helmet");
            assertThat(Layout.armourName(3)).isEqualTo("Boots");
            assertThat(Layout.at(Layout.OFF_HAND))
                    .contains(new Layout.Placed(Section.OFF_HAND, 0));
            assertThat(Layout.at(Layout.ENDER_CHEST))
                    .contains(new Layout.Placed(Section.ENDER_CHEST, 0));
        }

        @Test
        @DisplayName("the divider row is nothing at all, so a click there means nothing")
        void theDivider() {
            for (int slot = 36; slot < 45; slot++) {
                assertThat(Layout.at(slot))
                        .as("slot " + slot + " is chrome; rounding a click there to the nearest "
                                + "real slot is how somebody unequips a helmet by missing")
                        .isEmpty();
            }
            assertThat(Layout.chromeSlots()).contains(36, 44);
        }

        @Test
        @DisplayName("a window slot maps back to the right place in the real inventory")
        void mapsBackToTheInventory() {
            assertThat(Layout.at(27).orElseThrow().rawSlot())
                    .as("the first hotbar slot in the window is slot 0 in the inventory")
                    .isZero();
            assertThat(Layout.at(0).orElseThrow().rawSlot())
                    .as("the first backpack slot in the window is slot 9 in the inventory")
                    .isEqualTo(9);
            assertThat(Layout.at(Layout.ARMOUR_FIRST).orElseThrow().rawSlot())
                    .as("the helmet is slot 39, not 36")
                    .isEqualTo(39);
        }

        @Test
        @DisplayName("every real slot has exactly one place in the window")
        void everythingIsDrawnOnce() {
            java.util.Set<Integer> raw = new java.util.HashSet<>();
            for (int slot = 0; slot < Layout.SIZE; slot++) {
                Layout.at(slot).ifPresent(placed -> {
                    if (placed.section() != Section.ENDER_CHEST) {
                        assertThat(raw.add(placed.rawSlot()))
                                .as("slot " + placed.rawSlot() + " is drawn in two places")
                                .isTrue();
                    }
                });
            }
            assertThat(raw)
                    .as("thirty-six carried slots, four worn, one off-hand — anything missing is "
                            + "an item a moderator cannot see")
                    .hasSize(41);
        }

        @Test
        @DisplayName("a slot outside the window is nothing rather than a guess")
        void outsideTheWindow() {
            assertThat(Layout.at(-1)).isEmpty();
            assertThat(Layout.at(Layout.SIZE)).isEmpty();
        }
    }
}
