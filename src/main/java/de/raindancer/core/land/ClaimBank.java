package de.raindancer.core.land;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds entry fees collected for a claim until an owner withdraws them.
 * <p>
 * Paying visitors must not have their items vanish just because every owner happens to be offline, so
 * fees are banked rather than pushed into an inventory.
 */
public final class ClaimBank {

    private final List<ItemStack> items = new ArrayList<>();
    private int experiencePoints;

    public List<ItemStack> items() {
        return Collections.unmodifiableList(items);
    }

    public int experiencePoints() {
        return experiencePoints;
    }

    public boolean isEmpty() {
        return items.isEmpty() && experiencePoints <= 0;
    }

    public void depositItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        ItemStack incoming = stack.clone();
        for (ItemStack existing : items) {
            if (existing.isSimilar(incoming)) {
                int room = existing.getMaxStackSize() - existing.getAmount();
                if (room <= 0) {
                    continue;
                }
                int moved = Math.min(room, incoming.getAmount());
                existing.setAmount(existing.getAmount() + moved);
                incoming.setAmount(incoming.getAmount() - moved);
                if (incoming.getAmount() <= 0) {
                    return;
                }
            }
        }
        while (incoming.getAmount() > incoming.getMaxStackSize()) {
            ItemStack split = incoming.clone();
            split.setAmount(incoming.getMaxStackSize());
            items.add(split);
            incoming.setAmount(incoming.getAmount() - incoming.getMaxStackSize());
        }
        if (incoming.getAmount() > 0) {
            items.add(incoming);
        }
    }

    public void depositExperience(int points) {
        if (points > 0) {
            experiencePoints += points;
        }
    }

    /** Removes and returns everything in the bank. */
    public List<ItemStack> withdrawItems() {
        List<ItemStack> withdrawn = new ArrayList<>(items);
        items.clear();
        return withdrawn;
    }

    /** Removes and returns a single stack by index, or {@code null} when the index is stale. */
    public ItemStack withdrawItem(int index) {
        if (index < 0 || index >= items.size()) {
            return null;
        }
        return items.remove(index);
    }

    public int withdrawExperience() {
        int points = experiencePoints;
        experiencePoints = 0;
        return points;
    }

    public void restore(List<ItemStack> stacks, int points) {
        items.clear();
        if (stacks != null) {
            for (ItemStack stack : stacks) {
                if (stack != null && !stack.getType().isAir()) {
                    items.add(stack.clone());
                }
            }
        }
        experiencePoints = Math.max(0, points);
    }

    /** Puts a stack back, used when an owner's inventory was full mid-withdrawal. */
    public void returnItem(ItemStack stack) {
        depositItem(stack);
    }
}
