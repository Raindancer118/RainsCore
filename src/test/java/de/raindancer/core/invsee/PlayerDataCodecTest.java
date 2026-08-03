package de.raindancer.core.invsee;

import de.raindancer.core.nbt.Nbt;
import de.raindancer.core.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning what a saved player file says into what somebody is carrying, and back.
 *
 * <h2>Why this is worth its own class and its own tests</h2>
 * This is the only place in the offline path that has an opinion about anything, and every opinion
 * in it is one that silently ruins somebody's inventory when it is wrong:
 *
 * <ul>
 *   <li>The file numbers armour 100 to 103 and the off-hand −106. Read with the in-game numbers, a
 *       moderator sees an empty inventory; written with them, a helmet ends up on somebody's feet.</li>
 *   <li>An item in the file is the item's own compound with a {@code Slot} on it. An item the server
 *       will accept back is that compound with {@code Slot} gone and the file's {@code DataVersion}
 *       on it instead. Getting that swap wrong is an item that does not load.</li>
 *   <li>An entry this does not understand must survive being written back. Rebuilding the list from
 *       only what was understood is how a mod's extra slot quietly disappears.</li>
 * </ul>
 *
 * <p>Nothing here looks inside an item. That is deliberate: the item format really does change
 * between versions, and the server has a converter for it. This one only moves compounds about.
 */
@DisplayName("a saved player file")
class PlayerDataCodecTest {

    private static final int DATA_VERSION = 4189;

    private static Tag.Compound compound(Map<String, Tag> values) {
        return new Tag.Compound(new LinkedHashMap<>(values));
    }

    /** One entry as the game writes it: the item, with a Slot on it and no DataVersion. */
    private static Tag.Compound saved(String id, int count, int fileSlot) {
        Map<String, Tag> values = new LinkedHashMap<>();
        values.put("id", new Tag.Str(id));
        values.put("count", new Tag.Int(count));
        values.put("Slot", new Tag.Byte((byte) fileSlot));
        return new Tag.Compound(values);
    }

    private static Tag.Compound file(List<Tag> inventory, List<Tag> enderItems) {
        Map<String, Tag> values = new LinkedHashMap<>();
        values.put("DataVersion", new Tag.Int(DATA_VERSION));
        values.put("Health", new Tag.Float(20f));
        values.put("Inventory", Tag.List_.of(inventory));
        values.put("EnderItems", Tag.List_.of(enderItems));
        return new Tag.Compound(values);
    }

    /** What the server would be handed for one slot, unpacked again so a test can look at it. */
    private static Tag.Compound asItem(byte[] bytes) throws IOException {
        return Nbt.readCompressed(bytes);
    }

    @Nested
    @DisplayName("reading it")
    class Reading {

        @Test
        @DisplayName("the hotbar and the backpack land where they were")
        void carriedItems() throws IOException {
            Tag.Compound root = file(List.of(
                    saved("minecraft:diamond_sword", 1, 0),
                    saved("minecraft:bread", 5, 8),
                    saved("minecraft:cobblestone", 64, 9),
                    saved("minecraft:torch", 12, 35)), List.of());

            Carried<byte[]> carried = PlayerDataCodec.read(root);

            assertThat(asItem(carried.at(Section.HOTBAR, 0)).string("id"))
                    .contains("minecraft:diamond_sword");
            assertThat(asItem(carried.at(Section.HOTBAR, 8)).string("id")).contains("minecraft:bread");
            assertThat(asItem(carried.at(Section.STORAGE, 0)).string("id"))
                    .contains("minecraft:cobblestone");
            assertThat(asItem(carried.at(Section.STORAGE, 26)).string("id"))
                    .contains("minecraft:torch");
            assertThat(carried.count()).isEqualTo(4);
        }

        @Test
        @DisplayName("armour is turned the right way up")
        void armour() throws IOException {
            Tag.Compound root = file(List.of(
                    saved("minecraft:netherite_helmet", 1, 103),
                    saved("minecraft:leather_boots", 1, 100)), List.of());

            Carried<byte[]> carried = PlayerDataCodec.read(root);

            assertThat(asItem(carried.at(Section.ARMOUR, 0)).string("id"))
                    .as("the first armour place is the helmet, and the file calls that 103")
                    .contains("minecraft:netherite_helmet");
            assertThat(asItem(carried.at(Section.ARMOUR, 3)).string("id"))
                    .contains("minecraft:leather_boots");
            assertThat(carried.at(Section.ARMOUR, 1)).isNull();
        }

        @Test
        @DisplayName("the off-hand, which the file gives a negative number")
        void offHand() throws IOException {
            Tag.Compound root = file(List.of(saved("minecraft:shield", 1, -106)), List.of());

            assertThat(asItem(PlayerDataCodec.read(root).at(Section.OFF_HAND, 0)).string("id"))
                    .contains("minecraft:shield");
        }

        @Test
        @DisplayName("the ender chest, which is a different list with the same slot numbers")
        void enderChest() throws IOException {
            Tag.Compound root = file(
                    List.of(saved("minecraft:stone", 1, 0)),
                    List.of(saved("minecraft:emerald", 3, 0), saved("minecraft:gold_ingot", 1, 26)));

            Carried<byte[]> carried = PlayerDataCodec.read(root);

            assertThat(asItem(carried.at(Section.ENDER_CHEST, 0)).string("id"))
                    .as("slot 0 of the ender chest is not slot 0 of the hotbar, and only which "
                            + "list an entry came from says which is which")
                    .contains("minecraft:emerald");
            assertThat(asItem(carried.at(Section.ENDER_CHEST, 26)).string("id"))
                    .contains("minecraft:gold_ingot");
            assertThat(asItem(carried.at(Section.HOTBAR, 0)).string("id")).contains("minecraft:stone");
        }

        @Test
        @DisplayName("an item comes out as the server expects it: no Slot, with the DataVersion")
        void itemsAreHandedOverInTheServersOwnForm() throws IOException {
            Tag.Compound root = file(List.of(saved("minecraft:stone", 1, 3)), List.of());

            Tag.Compound item = asItem(PlayerDataCodec.read(root).at(Section.HOTBAR, 3));

            assertThat(item.has("Slot"))
                    .as("Slot is the file's business, not the item's; leaving it on is an item the "
                            + "server may refuse")
                    .isFalse();
            assertThat(item.intOr("DataVersion", -1))
                    .as("without this the server cannot know which version's item this is, and "
                            + "cannot bring an old one forward")
                    .isEqualTo(DATA_VERSION);
            assertThat(item.string("id")).contains("minecraft:stone");
        }

        @Test
        @DisplayName("everything else about the item is passed through untouched")
        void doesNotInterpretItems() throws IOException {
            Tag.Compound components = compound(Map.of(
                    "minecraft:custom_name", new Tag.Str("Excalibur"),
                    "minecraft:damage", new Tag.Int(12)));
            Map<String, Tag> entry = new LinkedHashMap<>();
            entry.put("id", new Tag.Str("minecraft:diamond_sword"));
            entry.put("count", new Tag.Int(1));
            entry.put("components", components);
            entry.put("Slot", new Tag.Byte((byte) 0));

            Tag.Compound item = asItem(PlayerDataCodec.read(
                    file(List.of(new Tag.Compound(entry)), List.of())).at(Section.HOTBAR, 0));

            assertThat(item.compound("components")).contains(components);
        }

        @Test
        @DisplayName("a file with nothing in it is somebody carrying nothing")
        void emptyFile() throws IOException {
            assertThat(PlayerDataCodec.read(Tag.Compound.empty()).isEmpty()).isTrue();
            assertThat(PlayerDataCodec.read(file(List.of(), List.of())).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("an entry the file should not contain is left alone rather than guessed at")
        void unknownSlots() throws IOException {
            Tag.Compound root = file(List.of(
                    saved("minecraft:stone", 1, 0),
                    saved("minecraft:mystery", 1, 45)), List.of());

            Carried<byte[]> carried = PlayerDataCodec.read(root);

            assertThat(carried.count())
                    .as("45 is a slot the vanilla file has no meaning for; putting it somewhere "
                            + "anyway moves an item nobody moved")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an entry with no slot at all does not become slot zero")
        void entryWithoutSlot() throws IOException {
            Tag.Compound noSlot = compound(Map.of("id", new Tag.Str("minecraft:stone")));

            assertThat(PlayerDataCodec.read(file(List.of(noSlot), List.of())).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a file whose lists are not lists does not throw")
        void wrongShapes() throws IOException {
            Tag.Compound root = compound(Map.of(
                    "Inventory", new Tag.Str("not a list"),
                    "EnderItems", new Tag.Int(3)));

            assertThat(PlayerDataCodec.read(root).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("writing it back")
    class Writing {

        @Test
        @DisplayName("what was read is what is written, byte for byte")
        void roundTrip() throws IOException {
            Tag.Compound root = file(List.of(
                    saved("minecraft:diamond_sword", 1, 0),
                    saved("minecraft:cobblestone", 64, 9),
                    saved("minecraft:netherite_helmet", 1, 103),
                    saved("minecraft:leather_boots", 1, 100),
                    saved("minecraft:shield", 1, -106)),
                    List.of(saved("minecraft:emerald", 3, 5)));

            Carried<byte[]> carried = PlayerDataCodec.read(root);
            Tag.Compound written = PlayerDataCodec.write(root, carried);

            assertThat(PlayerDataCodec.read(written))
                    .as("a round trip that loses or moves anything is an inventory somebody has "
                            + "to be given back by hand")
                    .isEqualTo(carried);
            assertThat(slotsIn(written, "Inventory"))
                    .containsExactlyInAnyOrder(0, 9, 103, 100, -106);
            assertThat(slotsIn(written, "EnderItems")).containsExactly(5);
        }

        @Test
        @DisplayName("the rest of the player is not touched")
        void keepsEverythingElse() throws IOException {
            Tag.Compound root = file(List.of(saved("minecraft:stone", 1, 0)), List.of())
                    .with("XpLevel", new Tag.Int(30))
                    .with("Pos", Tag.List_.of(List.of(new Tag.Double(1), new Tag.Double(64),
                            new Tag.Double(-3))));

            Tag.Compound written = PlayerDataCodec.write(root, PlayerDataCodec.read(root));

            assertThat(written.intOr("XpLevel", -1))
                    .as("everything a player is beyond their inventory has to come through this "
                            + "unchanged — experience, position, health, the lot")
                    .isEqualTo(30);
            assertThat(written.get("Pos")).isEqualTo(root.get("Pos"));
            assertThat(written.get("Health")).isEqualTo(root.get("Health"));
            assertThat(written.intOr("DataVersion", -1)).isEqualTo(DATA_VERSION);
        }

        @Test
        @DisplayName("an item put in by a moderator is written where they put it")
        void writesAChange() throws IOException {
            Tag.Compound root = file(List.of(), List.of());
            byte[] item = Nbt.writeCompressed(compound(Map.of(
                    "id", new Tag.Str("minecraft:cake"),
                    "count", new Tag.Int(1),
                    "DataVersion", new Tag.Int(DATA_VERSION))));

            Tag.Compound written = PlayerDataCodec.write(root,
                    Carried.<byte[]>empty().with(Section.ARMOUR, 0, item));

            List<Tag> entries = written.list("Inventory").orElseThrow().items();
            assertThat(entries).hasSize(1);
            Tag.Compound entry = (Tag.Compound) entries.get(0);
            assertThat(entry.number("Slot"))
                    .as("the helmet place is 103 in the file, not 39 and not 0")
                    .contains(103L);
            assertThat(entry.has("DataVersion"))
                    .as("DataVersion belongs to the file, not to each item in it")
                    .isFalse();
            assertThat(entry.string("id")).contains("minecraft:cake");
        }

        @Test
        @DisplayName("taking something out removes its entry rather than leaving an empty one")
        void writesARemoval() throws IOException {
            Tag.Compound root = file(List.of(
                    saved("minecraft:stone", 1, 0),
                    saved("minecraft:dirt", 1, 1)), List.of());

            Carried<byte[]> without = PlayerDataCodec.read(root).with(Section.HOTBAR, 0, null);
            Tag.Compound written = PlayerDataCodec.write(root, without);

            assertThat(slotsIn(written, "Inventory")).containsExactly(1);
        }

        @Test
        @DisplayName("an emptied inventory is an empty list, not a missing one")
        void writesAnEmptyInventory() throws IOException {
            Tag.Compound root = file(List.of(saved("minecraft:stone", 1, 0)), List.of());

            Tag.Compound written = PlayerDataCodec.write(root, Carried.empty());

            assertThat(written.list("Inventory")).isPresent();
            assertThat(written.list("Inventory").orElseThrow().size()).isZero();
            assertThat(written.list("EnderItems")).isPresent();
        }

        @Test
        @DisplayName("an entry this did not understand survives being written back")
        void keepsWhatItDoesNotUnderstand() throws IOException {
            Tag.Compound root = file(List.of(
                    saved("minecraft:stone", 1, 0),
                    saved("some_mod:extra_pocket", 1, 45)), List.of());

            Tag.Compound written = PlayerDataCodec.write(root, PlayerDataCodec.read(root));

            assertThat(slotsIn(written, "Inventory"))
                    .as("rebuilding the list from only what was understood is how a mod's extra "
                            + "slot quietly disappears the first time a moderator looks at "
                            + "somebody")
                    .containsExactlyInAnyOrder(0, 45);
        }

        @Test
        @DisplayName("a file that had no inventory at all still gets one")
        void writesIntoAFileWithoutLists() throws IOException {
            Tag.Compound root = compound(Map.of("DataVersion", new Tag.Int(DATA_VERSION)));
            byte[] item = Nbt.writeCompressed(compound(Map.of("id", new Tag.Str("minecraft:stone"))));

            Tag.Compound written = PlayerDataCodec.write(root,
                    Carried.<byte[]>empty().with(Section.HOTBAR, 4, item));

            assertThat(slotsIn(written, "Inventory")).containsExactly(4);
        }

        @Test
        @DisplayName("entries come out in slot order, so two saves can be compared")
        void writesInOrder() throws IOException {
            Tag.Compound root = file(List.of(
                    saved("minecraft:c", 1, 9),
                    saved("minecraft:b", 1, 103),
                    saved("minecraft:a", 1, 0)), List.of());

            assertThat(slotsIn(PlayerDataCodec.write(root, PlayerDataCodec.read(root)), "Inventory"))
                    .as("a file rewritten with its entries shuffled is a needless diff between "
                            + "two saves that are the same")
                    .containsExactly(0, 9, 103);
        }

        private static List<Integer> slotsIn(Tag.Compound root, String listName) {
            List<Integer> slots = new ArrayList<>();
            for (Tag entry : root.list(listName).orElseThrow().items()) {
                slots.add(((Tag.Compound) entry).intOr("Slot", Integer.MIN_VALUE));
            }
            return slots;
        }
    }
}
