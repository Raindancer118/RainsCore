package de.raindancer.core.moderation.invsee;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * What somebody who is online is carrying, read straight off them.
 *
 * <h2>The one thing this has to get right</h2>
 * The inventory array is not laid out the way anybody would guess — the hotbar is 0 to 8 but drawn
 * at the bottom, armour is 36 to 39 in the order boots-first, and the off-hand is 40. All of that is
 * written down once in {@link Slots}, and this reads through it rather than counting for itself, so
 * there is exactly one place in the codebase that can have it wrong.
 *
 * <p>The ender chest is a separate inventory that is easy to forget and is where anybody hiding
 * something puts it.
 */
public final class OnlineInventorySource implements InventorySource {

    private final Function<UUID, Player> players;

    public OnlineInventorySource() {
        this(Bukkit::getPlayer);
    }

    /** @param players how to find somebody — the seam a test replaces */
    public OnlineInventorySource(Function<UUID, Player> players) {
        this.players = players;
    }

    @Override
    public Optional<Carried<ItemStack>> read(UUID who) {
        Player player = who == null ? null : players.apply(who);
        if (player == null) {
            return Optional.empty();
        }
        Carried<ItemStack> carried = Carried.empty();
        Inventory inventory = player.getInventory();
        for (Section section : Section.values()) {
            if (section.isSeparate()) {
                continue;
            }
            for (int within = 0; within < section.size(); within++) {
                carried = carried.with(section, within,
                        copyOf(inventory.getItem(Slots.rawSlot(section, within))));
            }
        }
        Inventory ender = player.getEnderChest();
        for (int within = 0; within < Section.ENDER_CHEST.size(); within++) {
            carried = carried.with(Section.ENDER_CHEST, within, copyOf(ender.getItem(within)));
        }
        return Optional.of(carried);
    }

    @Override
    public boolean set(UUID who, Section section, int indexWithin, ItemStack item) {
        Player player = who == null ? null : players.apply(who);
        if (player == null || section == null || indexWithin < 0
                || indexWithin >= section.size()) {
            return false;
        }
        if (section.isSeparate()) {
            player.getEnderChest().setItem(indexWithin, item);
        } else {
            player.getInventory().setItem(Slots.rawSlot(section, indexWithin), item);
        }
        return true;
    }

    @Override
    public boolean write(UUID who, Carried<ItemStack> carried) {
        Player player = who == null ? null : players.apply(who);
        if (player == null || carried == null) {
            return false;
        }
        for (Section section : Section.values()) {
            for (int within = 0; within < section.size(); within++) {
                set(who, section, within, carried.at(section, within));
            }
        }
        return true;
    }

    @Override
    public String describe() {
        return "their inventory";
    }

    /**
     * A copy, so the snapshot is one.
     *
     * <p>Bukkit hands back a live view for some slots and a copy for others, and the difference is
     * not documented. A snapshot holding the live one would change underneath a read-only window.
     */
    private static ItemStack copyOf(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }
}
