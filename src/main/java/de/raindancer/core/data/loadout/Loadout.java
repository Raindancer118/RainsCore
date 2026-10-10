package de.raindancer.core.data.loadout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything a player carries and is, apart from who they are: every inventory slot, the ender chest,
 * experience, health, hunger, game mode, flight and potion effects — and optionally where they stood.
 *
 * <p>The thing to swap when one player needs two separate lives on one server: an admin mode, a minigame
 * that borrows somebody and must hand them back exactly as they came. Plain data, items already encoded
 * (see {@link Loadouts}), so it can be written to disk on any thread.
 *
 * <p>Slots are positions, not items: an empty one is {@code ""}, never left out, so a sword in slot 4
 * comes back in slot 4.
 *
 * @param inventory  {@code PlayerInventory#getContents()} — hotbar and storage, armour, off hand
 * @param gameMode   a {@code GameMode} name
 * @param place      where they stood, or null when that is not part of this loadout
 * @param data       what plugins keep per side ({@link SideData}) — or null for a loadout saved before there was
 *                   any, which then leaves the player's side data as it is rather than clearing it
 */
public record Loadout(List<String> inventory, List<String> enderChest,
                      int level, float exp, double health, int food, float saturation,
                      String gameMode, boolean allowFlight, boolean flying,
                      List<Effect> effects, Place place, java.util.Map<String, String> data) {

    /** Without side data — as loadouts were before there was any. */
    public Loadout(List<String> inventory, List<String> enderChest, int level, float exp, double health, int food,
                   float saturation, String gameMode, boolean allowFlight, boolean flying, List<Effect> effects,
                   Place place) {
        this(inventory, enderChest, level, exp, health, food, saturation, gameMode, allowFlight, flying, effects,
                place, null);
    }

    /** 36 storage slots, 4 armour, 1 off hand. */
    public static final int INVENTORY_SLOTS = 41;
    public static final int ENDER_SLOTS = 27;

    /** One potion effect, its type as a namespaced key. */
    public record Effect(String type, int ticks, int amplifier, boolean ambient, boolean particles,
                         boolean icon) {
    }

    public record Place(String world, double x, double y, double z, float yaw, float pitch) {
    }

    public Loadout {
        inventory = slots(inventory, INVENTORY_SLOTS);
        enderChest = slots(enderChest, ENDER_SLOTS);
        level = Math.max(0, level);
        exp = Math.max(0f, Math.min(1f, exp));
        health = Math.max(0.5, health);
        food = Math.max(0, Math.min(20, food));
        saturation = Math.max(0f, saturation);
        gameMode = gameMode == null || gameMode.isBlank() ? "SURVIVAL" : gameMode;
        effects = effects == null ? List.of() : List.copyOf(effects);
        data = data == null ? null : java.util.Map.copyOf(data);
    }

    /** Nothing carried, full health and food, nothing active — somebody's first time in a profile. */
    public static Loadout empty(String gameMode) {
        return new Loadout(List.of(), List.of(), 0, 0f, 20.0, 20, 5f, gameMode, false, false, List.of(), null,
                java.util.Map.of());
    }

    public Loadout withPlace(Place place) {
        return new Loadout(inventory, enderChest, level, exp, health, food, saturation, gameMode, allowFlight,
                flying, effects, place, data);
    }

    public boolean hasItems() {
        return inventory.stream().anyMatch(slot -> !slot.isEmpty())
                || enderChest.stream().anyMatch(slot -> !slot.isEmpty());
    }

    /** Padded to {@code size}; a longer list is kept whole, because cutting it would delete items. */
    private static List<String> slots(List<String> given, int size) {
        List<String> padded = new ArrayList<>(Collections.nCopies(size, ""));
        if (given != null) {
            for (int slot = 0; slot < given.size(); slot++) {
                String line = given.get(slot) == null ? "" : given.get(slot);
                if (slot < size) {
                    padded.set(slot, line);
                } else {
                    padded.add(line);
                }
            }
        }
        return List.copyOf(padded);
    }
}
