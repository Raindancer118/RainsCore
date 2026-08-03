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

    private Slots() {
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
