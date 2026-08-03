package de.raindancer.core.invsee;

import java.util.Optional;

/**
 * Which part of an inventory a raw slot number belongs to, and where to draw it.
 *
 * <h2>Why this is a class and not four constants</h2>
 * Because the layout of a player's inventory is genuinely surprising and nothing about it is
 * guessable: the hotbar is slots 0–8 but is drawn at the <em>bottom</em>, storage is 9–35 and is
 * drawn above it, armour is 36–39 in the order boots, leggings, chestplate, helmet — the reverse of
 * how anybody would list them — and the off-hand is 40 on its own.
 *
 * <p>Every plugin that has ever shown somebody's inventory has got at least one of those wrong, and
 * the symptom is a moderator unequipping a helmet by clicking what looked like an empty slot.
 */
public final class Slots {

    /** Where each part starts in a player's inventory array. */
    public static final int HOTBAR_FIRST = 0;
    public static final int STORAGE_FIRST = 9;
    public static final int ARMOUR_FIRST = 36;
    public static final int OFF_HAND = 40;

    /**
     * Where armour starts in the <em>save file</em>, which is a different set of numbers entirely.
     *
     * <p>{@code player.dat} stores armour as 100 to 103 and the off-hand as −106, while a running
     * game calls the same four pieces 36 to 39 and the same off-hand 40. Nothing announces this: a
     * reader written against the in-game numbers finds nothing at all in a file, and a writer using
     * them puts a helmet on somebody's feet — permanently, because the file is what the player logs
     * back in to.
     */
    public static final int FILE_ARMOUR_FIRST = 100;
    /** The boots end. 103 is the helmet. */
    public static final int FILE_ARMOUR_LAST = 103;
    public static final int FILE_OFF_HAND = -106;

    private Slots() {
    }

    // ----------------------------------------------------------------- the numbers on disk

    /**
     * Which part a slot number in a saved {@code Inventory} list belongs to.
     *
     * <p>The ender chest is not here: it is its own list in the file, numbered 0 to 26, which is the
     * same range as the hotbar and the backpack. Which list an entry came from is the only thing
     * that tells them apart, so that stays the caller's business rather than being guessed at here.
     *
     * @return empty for a number the file should not contain — better than rounding it to the
     *         nearest real part, which is how an item moves by itself
     */
    public static Optional<Section> sectionOfFileSlot(int fileSlot) {
        if (fileSlot >= 0 && fileSlot < STORAGE_FIRST) {
            return Optional.of(Section.HOTBAR);
        }
        if (fileSlot >= STORAGE_FIRST && fileSlot < ARMOUR_FIRST) {
            return Optional.of(Section.STORAGE);
        }
        if (fileSlot >= FILE_ARMOUR_FIRST && fileSlot <= FILE_ARMOUR_LAST) {
            return Optional.of(Section.ARMOUR);
        }
        if (fileSlot == FILE_OFF_HAND) {
            return Optional.of(Section.OFF_HAND);
        }
        return Optional.empty();
    }

    /** Where a saved slot number sits within its part, or −1 when it is not one. */
    public static int indexWithinFileSlot(int fileSlot) {
        return sectionOfFileSlot(fileSlot).map(section -> switch (section) {
            case HOTBAR -> fileSlot;
            case STORAGE -> fileSlot - STORAGE_FIRST;
            // Helmet first, as everywhere else here — the file has it the other way up.
            case ARMOUR -> FILE_ARMOUR_LAST - fileSlot;
            case OFF_HAND, ENDER_CHEST -> 0;
        }).orElse(-1);
    }

    /** The number the save file uses for one place within a part. */
    public static int fileSlot(Section section, int indexWithin) {
        return switch (section) {
            case HOTBAR -> indexWithin;
            case STORAGE -> STORAGE_FIRST + indexWithin;
            case ARMOUR -> FILE_ARMOUR_LAST - indexWithin;
            case OFF_HAND -> FILE_OFF_HAND;
            // Its own list, numbered from zero.
            case ENDER_CHEST -> indexWithin;
        };
    }

    /** Which part a raw inventory slot belongs to. */
    public static Optional<Section> sectionOf(int rawSlot) {
        if (rawSlot >= HOTBAR_FIRST && rawSlot < STORAGE_FIRST) {
            return Optional.of(Section.HOTBAR);
        }
        if (rawSlot >= STORAGE_FIRST && rawSlot < ARMOUR_FIRST) {
            return Optional.of(Section.STORAGE);
        }
        if (rawSlot >= ARMOUR_FIRST && rawSlot < OFF_HAND) {
            return Optional.of(Section.ARMOUR);
        }
        if (rawSlot == OFF_HAND) {
            return Optional.of(Section.OFF_HAND);
        }
        return Optional.empty();
    }

    /** Where a slot sits within its own part — the third hotbar slot, the second armour piece. */
    public static int indexWithin(int rawSlot) {
        return sectionOf(rawSlot).map(section -> switch (section) {
            case HOTBAR -> rawSlot - HOTBAR_FIRST;
            case STORAGE -> rawSlot - STORAGE_FIRST;
            case ARMOUR -> armourIndex(rawSlot);
            case OFF_HAND -> 0;
            case ENDER_CHEST -> rawSlot;
        }).orElse(-1);
    }

    /**
     * Armour, in the order a person would list it: helmet, chestplate, leggings, boots.
     *
     * <p>The array is the other way round — 36 is the boots — which is the single most reliable way
     * to draw somebody's armour upside down.
     */
    public static int armourIndex(int rawSlot) {
        return 3 - (rawSlot - ARMOUR_FIRST);
    }

    /** The raw slot of one armour piece, counting from the helmet. */
    public static int armourSlot(int fromHelmet) {
        return ARMOUR_FIRST + (3 - fromHelmet);
    }

    /** The raw slot of one place within a part. */
    public static int rawSlot(Section section, int indexWithin) {
        return switch (section) {
            case HOTBAR -> HOTBAR_FIRST + indexWithin;
            case STORAGE -> STORAGE_FIRST + indexWithin;
            case ARMOUR -> armourSlot(indexWithin);
            case OFF_HAND -> OFF_HAND;
            case ENDER_CHEST -> indexWithin;
        };
    }
}
