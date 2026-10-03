package de.raindancer.core.moderation.invsee;

import de.raindancer.core.data.nbt.ItemBytes;
import de.raindancer.core.data.nbt.Nbt;
import de.raindancer.core.data.nbt.Tag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * What a saved player file says somebody is carrying, and how to put it back.
 *
 * <h2>Why this touches everything except the items</h2>
 * The one thing that genuinely changes between Minecraft versions is the shape of an item. The file
 * around it — a compound, two lists, a {@code Slot} on each entry — has been the same for over a
 * decade. So this moves compounds about and never opens one: an item comes out as the exact bytes
 * the server's own reader expects, and goes back in as the exact bytes it produced.
 *
 * <p>Concretely, the swap in each direction is two names:
 * <ul>
 *   <li><b>Out of the file:</b> take {@code Slot} off the entry and put the file's
 *       {@code DataVersion} on, then gzip it. That is precisely what
 *       {@code ItemStack.deserializeBytes} reads, and it is what lets the server bring an item
 *       written by an older version forward without this class knowing anything about how.</li>
 *   <li><b>Into the file:</b> take {@code DataVersion} off and put {@code Slot} on.</li>
 * </ul>
 *
 * <h2>The two rules that stop items disappearing</h2>
 * <ul>
 *   <li>An entry whose slot number means nothing here is <b>not placed</b> — a vanilla file has no
 *       slot 45, and putting it somewhere anyway moves an item nobody moved.</li>
 *   <li>That same entry is <b>kept</b> and written back untouched. Rebuilding the list out of only
 *       what was understood is how a mod's extra slot quietly disappears the first time a moderator
 *       looks at somebody.</li>
 * </ul>
 */
public final class PlayerDataCodec {

    /** What the game calls the carried inventory in a player file. */
    public static final String INVENTORY = "Inventory";
    /** And the ender chest, which is a separate list numbered from zero again. */
    public static final String ENDER_ITEMS = "EnderItems";
    public static final String DATA_VERSION = "DataVersion";
    public static final String SLOT = "Slot";
    /**
     * Where armour and the off-hand live from Minecraft 1.21.5 on: a compound keyed by slot name
     * rather than entries 100–103 and −106 in {@link #INVENTORY}, which a newer server no longer
     * writes and ignores on load.
     */
    public static final String EQUIPMENT = "equipment";
    /** The first data version (1.21.5) that keeps armour under {@link #EQUIPMENT}. */
    public static final int EQUIPMENT_SINCE = 4325;
    /** The names {@link #EQUIPMENT} uses for the armour, helmet first like {@link Section#ARMOUR}. */
    private static final List<String> ARMOUR_KEYS = List.of("head", "chest", "legs", "feet");
    private static final String OFF_HAND_KEY = "offhand";

    private PlayerDataCodec() {
    }

    /** Which version of the game last wrote this file, or 0 when it does not say. */
    public static int dataVersionOf(Tag.Compound root) {
        return root == null ? 0 : root.intOr(DATA_VERSION, 0);
    }

    // ----------------------------------------------------------------------------- reading

    /**
     * What the file says somebody is carrying, each item as the bytes the server reads back.
     *
     * @throws IOException only if an item compound cannot be written out again, which would mean
     *                     the file held something the format cannot express
     */
    public static Carried<byte[]> read(Tag.Compound root) throws IOException {
        if (root == null) {
            return Carried.empty();
        }
        int dataVersion = dataVersionOf(root);
        Carried<byte[]> carried = Carried.empty();
        for (Tag.Compound entry : entriesOf(root, INVENTORY)) {
            Optional<Integer> slot = slotOf(entry);
            if (slot.isEmpty()) {
                continue;
            }
            Optional<Section> section = Slots.sectionOfFileSlot(slot.get());
            if (section.isEmpty()) {
                continue;
            }
            carried = carried.with(section.get(), Slots.indexWithinFileSlot(slot.get()),
                    asItemBytes(entry, dataVersion));
        }
        for (Tag.Compound entry : entriesOf(root, ENDER_ITEMS)) {
            Optional<Integer> slot = slotOf(entry);
            if (slot.isEmpty() || slot.get() < 0 || slot.get() >= Section.ENDER_CHEST.size()) {
                continue;
            }
            carried = carried.with(Section.ENDER_CHEST, slot.get(),
                    asItemBytes(entry, dataVersion));
        }
        Optional<Tag.Compound> equipment = root.compound(EQUIPMENT);
        if (equipment.isPresent()) {
            for (int within = 0; within < ARMOUR_KEYS.size(); within++) {
                Optional<Tag.Compound> worn = equipment.get().compound(ARMOUR_KEYS.get(within));
                if (worn.isPresent()) {
                    carried = carried.with(Section.ARMOUR, within,
                            asItemBytes(worn.get(), dataVersion));
                }
            }
            Optional<Tag.Compound> held = equipment.get().compound(OFF_HAND_KEY);
            if (held.isPresent()) {
                carried = carried.with(Section.OFF_HAND, 0, asItemBytes(held.get(), dataVersion));
            }
        }
        return carried;
    }

    /** Whether this file keeps armour and the off-hand under {@link #EQUIPMENT}. */
    private static boolean keepsEquipmentApart(Tag.Compound root) {
        return root.has(EQUIPMENT) || dataVersionOf(root) >= EQUIPMENT_SINCE;
    }

    /** One saved entry as the bytes the server's own item reader takes. */
    private static byte[] asItemBytes(Tag.Compound entry, int dataVersion) throws IOException {
        return Nbt.writeCompressed(entry.without(SLOT).with(DATA_VERSION, new Tag.Int(dataVersion)));
    }

    // ----------------------------------------------------------------------------- writing

    /**
     * The same file with a new inventory in it.
     *
     * <p>Everything else about the player — where they are, what they know, how hurt they are — is
     * carried through untouched, because this is handed the whole file and gives back the whole
     * file. A codec that built a fresh compound would be a codec that deleted a player's experience
     * the first time somebody straightened out their backpack.
     *
     * @param root    the file as it was read
     * @param carried what it should now say, each item as the bytes the server produced
     */
    public static Tag.Compound write(Tag.Compound root, Carried<byte[]> carried) throws IOException {
        Tag.Compound file = root == null ? Tag.Compound.empty() : root;
        Carried<byte[]> items = carried == null ? Carried.empty() : carried;

        boolean equipmentApart = keepsEquipmentApart(file);
        List<Tag> inventory = new ArrayList<>();
        List<Section> inInventory = equipmentApart ? List.of(Section.HOTBAR, Section.STORAGE)
                : List.of(Section.HOTBAR, Section.STORAGE, Section.ARMOUR, Section.OFF_HAND);
        for (Section section : inInventory) {
            for (int within = 0; within < section.size(); within++) {
                byte[] bytes = items.at(section, within);
                if (bytes != null) {
                    inventory.add(asSavedEntry(bytes, Slots.fileSlot(section, within)));
                }
            }
        }
        inventory.addAll(keptFrom(file, INVENTORY,
                slot -> Slots.sectionOfFileSlot(slot).isEmpty()));

        List<Tag> ender = new ArrayList<>();
        for (int within = 0; within < Section.ENDER_CHEST.size(); within++) {
            byte[] bytes = items.at(Section.ENDER_CHEST, within);
            if (bytes != null) {
                ender.add(asSavedEntry(bytes, within));
            }
        }
        ender.addAll(keptFrom(file, ENDER_ITEMS,
                slot -> slot < 0 || slot >= Section.ENDER_CHEST.size()));

        Tag.Compound written = file.with(INVENTORY, Tag.List_.of(inventory))
                .with(ENDER_ITEMS, Tag.List_.of(ender));
        return equipmentApart ? written.with(EQUIPMENT, equipment(file, items)) : written;
    }

    /**
     * The file's equipment with this window's slots replaced. Anything else in it — a saddle, body
     * armour — is not shown here, so it is carried through rather than dropped.
     */
    private static Tag.Compound equipment(Tag.Compound file, Carried<byte[]> items)
            throws IOException {
        Tag.Compound equipment = file.compound(EQUIPMENT).orElse(Tag.Compound.empty());
        for (int within = 0; within < ARMOUR_KEYS.size(); within++) {
            equipment = withItem(equipment, ARMOUR_KEYS.get(within), items.at(Section.ARMOUR, within));
        }
        return withItem(equipment, OFF_HAND_KEY, items.at(Section.OFF_HAND, 0));
    }

    private static Tag.Compound withItem(Tag.Compound equipment, String key, byte[] bytes)
            throws IOException {
        return bytes == null ? equipment.without(key)
                : equipment.with(key, Nbt.readCompressed(bytes).without(DATA_VERSION));
    }

    /** One item's bytes as the file wants them: no DataVersion, with a Slot. */
    private static Tag.Compound asSavedEntry(byte[] bytes, int fileSlot) throws IOException {
        return Nbt.readCompressed(bytes)
                .without(DATA_VERSION)
                .with(SLOT, new Tag.Byte((byte) fileSlot));
    }

    /**
     * Entries from the original file that this has no place for and therefore must not drop.
     *
     * <p>An entry with no slot at all is kept too: it was in the file, this does not know what it
     * is, and deleting what you do not understand is not a safe default when the thing you do not
     * understand is somebody's property.
     */
    private static List<Tag> keptFrom(Tag.Compound root, String listName,
                                      IntPredicate unplaceable) {
        List<Tag> kept = new ArrayList<>();
        for (Tag.Compound entry : entriesOf(root, listName)) {
            Optional<Integer> slot = slotOf(entry);
            if (slot.isEmpty() || unplaceable.test(slot.get())) {
                kept.add(entry);
            }
        }
        return kept;
    }

    // ------------------------------------------------------------------------------ shared

    /** The compounds in one of the file's lists — nothing at all when it is missing or is not one. */
    private static List<Tag.Compound> entriesOf(Tag.Compound root, String listName) {
        List<Tag.Compound> entries = new ArrayList<>();
        root.list(listName).ifPresent(list -> {
            for (Tag item : list.items()) {
                if (item instanceof Tag.Compound entry) {
                    entries.add(entry);
                }
            }
        });
        return entries;
    }

    /** The slot an entry claims, or empty when it does not claim one. */
    private static Optional<Integer> slotOf(Tag.Compound entry) {
        return entry.number(SLOT).map(Long::intValue);
    }
}
