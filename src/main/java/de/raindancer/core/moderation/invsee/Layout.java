package de.raindancer.core.moderation.invsee;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where each part of somebody's inventory is drawn in the window.
 *
 * <h2>Why the layout is worked out rather than hardcoded</h2>
 * Because a window showing thirty-six identical squares tells a moderator nothing. What they need to
 * see at a glance is what somebody is <em>holding</em>, what is in their backpack, what they are
 * <em>wearing</em>, and what is in their ender chest — and those are four different questions that
 * happen to be stored in one array.
 *
 * <p>The arrangement mirrors the player's own screen, which is the one thing every player already
 * knows how to read: backpack on top, hotbar below it, and what they are wearing off to the side.
 *
 * <pre>
 *   row 0   backpack     ■ ■ ■ ■ ■ ■ ■ ■ ■
 *   row 1   backpack     ■ ■ ■ ■ ■ ■ ■ ■ ■
 *   row 2   backpack     ■ ■ ■ ■ ■ ■ ■ ■ ■
 *   row 3   hotbar       ■ ■ ■ ■ ■ ■ ■ ■ ■
 *   row 4   ─────────────────────────────────
 *   row 5   worn         H C L B · O · · ender
 * </pre>
 */
public final class Layout {

    /** Six rows, which is the largest window the game will open. */
    public static final int ROWS = 6;
    public static final int WIDTH = 9;
    public static final int SIZE = ROWS * WIDTH;

    /** Where the backpack starts — the top-left corner. */
    public static final int STORAGE_FIRST = 0;
    /** The hotbar, on its own row under the backpack, as it is on a player's own screen. */
    public static final int HOTBAR_FIRST = 27;
    /** A row of chrome between what they carry and what they wear, so the two do not blur. */
    public static final int DIVIDER_ROW = 4;
    /** Armour, helmet first, on the bottom row. */
    public static final int ARMOUR_FIRST = 45;
    /** The off-hand, one gap after the armour so it does not read as a fifth piece. */
    public static final int OFF_HAND = 50;
    /** The button that opens the ender chest, at the far end of the bottom row. */
    public static final int ENDER_CHEST = 53;

    private Layout() {
    }

    /** Where one place within a part is drawn. */
    public static int slotFor(Section section, int indexWithin) {
        return switch (section) {
            case STORAGE -> STORAGE_FIRST + indexWithin;
            case HOTBAR -> HOTBAR_FIRST + indexWithin;
            case ARMOUR -> ARMOUR_FIRST + indexWithin;
            case OFF_HAND -> OFF_HAND;
            case ENDER_CHEST -> ENDER_CHEST;
        };
    }

    /**
     * What a slot in the window is showing, or empty for chrome.
     *
     * <p>The call a click handler makes. Empty means the divider or a gap, and a click there is one
     * to swallow rather than to work out the meaning of.
     */
    public static Optional<Placed> at(int windowSlot) {
        if (windowSlot < 0 || windowSlot >= SIZE) {
            return Optional.empty();
        }
        if (windowSlot < HOTBAR_FIRST) {
            return Optional.of(new Placed(Section.STORAGE, windowSlot - STORAGE_FIRST));
        }
        if (windowSlot < HOTBAR_FIRST + WIDTH) {
            return Optional.of(new Placed(Section.HOTBAR, windowSlot - HOTBAR_FIRST));
        }
        if (windowSlot < ARMOUR_FIRST) {
            // The divider row. Deliberately nothing: a click here means nothing and should do
            // nothing, rather than being rounded to the nearest real slot.
            return Optional.empty();
        }
        if (windowSlot < ARMOUR_FIRST + Section.ARMOUR.size()) {
            return Optional.of(new Placed(Section.ARMOUR, windowSlot - ARMOUR_FIRST));
        }
        if (windowSlot == OFF_HAND) {
            return Optional.of(new Placed(Section.OFF_HAND, 0));
        }
        if (windowSlot == ENDER_CHEST) {
            return Optional.of(new Placed(Section.ENDER_CHEST, 0));
        }
        return Optional.empty();
    }

    /** Every window slot that is chrome rather than an item — the divider and the gaps. */
    public static java.util.List<Integer> chromeSlots() {
        java.util.List<Integer> chrome = new java.util.ArrayList<>();
        for (int slot = 0; slot < SIZE; slot++) {
            if (at(slot).isEmpty()) {
                chrome.add(slot);
            }
        }
        return chrome;
    }

    /** Which line of the message file names one armour place. */
    public static String armourKey(int indexFromHelmet) {
        return switch (indexFromHelmet) {
            case 0 -> "invsee.armour.helmet";
            case 1 -> "invsee.armour.chestplate";
            case 2 -> "invsee.armour.leggings";
            case 3 -> "invsee.armour.boots";
            default -> "invsee.armour.other";
        };
    }

    /** What the armour slots are called, top to bottom as somebody wears them. */
    public static String armourName(int indexFromHelmet) {
        return switch (indexFromHelmet) {
            case 0 -> "Helmet";
            case 1 -> "Chestplate";
            case 2 -> "Leggings";
            case 3 -> "Boots";
            default -> "Worn";
        };
    }

    /** The same, in whatever words this server uses. */
    public static String armourName(int indexFromHelmet,
                                    de.raindancer.core.ui.messages.Messages words) {
        return words == null ? armourName(indexFromHelmet) : words.raw(armourKey(indexFromHelmet));
    }

    /** Every part, and where it starts, for a window that wants to label them. */
    public static Map<Section, Integer> sectionStarts() {
        Map<Section, Integer> starts = new LinkedHashMap<>();
        starts.put(Section.STORAGE, STORAGE_FIRST);
        starts.put(Section.HOTBAR, HOTBAR_FIRST);
        starts.put(Section.ARMOUR, ARMOUR_FIRST);
        starts.put(Section.OFF_HAND, OFF_HAND);
        starts.put(Section.ENDER_CHEST, ENDER_CHEST);
        return starts;
    }

    /**
     * One place in the window.
     *
     * @param section     which part it belongs to
     * @param indexWithin where in that part — the third hotbar slot, the helmet
     */
    public record Placed(Section section, int indexWithin) {

        /** The raw inventory slot this is showing. */
        public int rawSlot() {
            return Slots.rawSlot(section, indexWithin);
        }
    }
}
