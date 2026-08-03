package de.raindancer.core.content.loot;

import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemFactory;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Putting a rolled loot table into an actual container.
 *
 * <h2>What is here and what is not</h2>
 * Only the part that needs a server. {@link LootTable} decides what comes out, how much of it and
 * which slots it goes in — all tested, including the weighting boundaries. This turns those values
 * into {@link ItemStack}s and writes them.
 *
 * <h2>Why a custom entry goes through {@link ItemFactory}</h2>
 * So a medikit found in a chest is the same item as one given by a command: the same key in its
 * persistent data container, and therefore the same thing to every plugin that checks. Building a
 * lookalike here would produce an item that looks right and is not recognised by the ability that
 * makes it useful.
 */
public final class LootFiller {

    private static final LogChannel log = Log.of("loot");

    private final CustomItems items;
    private final ItemFactory factory;

    public LootFiller(CustomItems items, ItemFactory factory) {
        this.items = items;
        this.factory = factory;
    }

    /**
     * Empties a container and fills it from a table.
     *
     * <p>Emptied first on purpose: a refilled chest that still holds what nobody took is a chest
     * that accumulates, and a supply drop is meant to be what the table says rather than that plus
     * whatever was left.
     *
     * @return how many slots were actually filled
     */
    public int fill(Inventory inventory, LootTable table, Random random) {
        if (inventory == null || table == null) {
            return 0;
        }
        inventory.clear();
        int placed = 0;
        for (LootRoll roll : table.roll(inventory.getSize(), random)) {
            Optional<ItemStack> stack = toStack(roll);
            if (stack.isEmpty()) {
                continue;
            }
            inventory.setItem(roll.slot(), stack.get());
            placed++;
        }
        return placed;
    }

    /** Fills without emptying, for a table that tops a container up rather than replacing it. */
    public int topUp(Inventory inventory, LootTable table, Random random) {
        if (inventory == null || table == null) {
            return 0;
        }
        int placed = 0;
        for (LootRoll roll : table.roll(inventory.getSize(), random)) {
            if (inventory.getItem(roll.slot()) != null) {
                // Somebody's item is in that slot. Leaving it is the only safe thing to do.
                continue;
            }
            Optional<ItemStack> stack = toStack(roll);
            if (stack.isEmpty()) {
                continue;
            }
            inventory.setItem(roll.slot(), stack.get());
            placed++;
        }
        return placed;
    }

    /** One roll as a real stack, or empty when it names something that no longer exists. */
    private Optional<ItemStack> toStack(LootRoll roll) {
        LootEntry entry = roll.entry();
        if (!entry.isCustom()) {
            return Optional.of(new ItemStack(entry.material(), roll.amount()));
        }
        Optional<ItemStack> made = items.byKey(entry.customKey())
                .flatMap(item -> factory.create(item, roll.amount()));
        if (made.isEmpty()) {
            // A table naming an item whose plugin is switched off is a config that has drifted, not
            // a crash: the slot is left empty and the owner is told which entry to fix.
            log.warn("A loot table gives '{}', which nothing defines; that slot is empty.",
                    entry.customKey());
        }
        return made;
    }

    /**
     * Which custom items a table names that nothing actually defines.
     *
     * <p>For a plugin to check at startup rather than discovering it when somebody opens a chest.
     */
    public List<String> missingItems(LootTable table) {
        return table.customItems().stream()
                .filter(key -> items.byKey(key).isEmpty())
                .toList();
    }
}
