package de.raindancer.core.land;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The claim's kit-out service: rules for what people should be carrying, and a stock to hand it out of.
 * <p>
 * Kept separate from the pantry and the potion store because it is a third kind of thing — the pantry is
 * eaten, the potion store is burnt by the claim itself, this is lent to players. It only ever tops
 * somebody up: nothing is taken away, and nothing is given if there is no room.
 */
public final class ClaimEquipment {

    private boolean enabled;

    private final List<EquipRule> rules = new ArrayList<>();
    private final List<ItemStack> stock = new ArrayList<>();

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<EquipRule> rules() {
        return Collections.unmodifiableList(rules);
    }

    public void addRule(EquipRule rule) {
        rules.add(rule);
    }

    public boolean removeRule(int index) {
        if (index < 0 || index >= rules.size()) {
            return false;
        }
        rules.remove(index);
        return true;
    }

    public void clearRules() {
        rules.clear();
    }

    public void restoreRules(List<EquipRule> loaded) {
        rules.clear();
        if (loaded != null) {
            rules.addAll(loaded);
        }
    }

    // ------------------------------------------------------------ stock

    public List<ItemStack> stock() {
        return Collections.unmodifiableList(stock);
    }

    public boolean stockEmpty() {
        return stock.isEmpty();
    }

    public int totalStock() {
        int total = 0;
        for (ItemStack item : stock) {
            total += item.getAmount();
        }
        return total;
    }

    /** How many of this exact item are left to hand out. */
    public int countMatching(ItemStack template) {
        int count = 0;
        for (ItemStack item : stock) {
            if (item.isSimilar(template)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    public int deposit(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }
        int accepted = item.getAmount();
        ItemStack incoming = item.clone();
        for (ItemStack existing : stock) {
            if (!existing.isSimilar(incoming)) {
                continue;
            }
            int room = existing.getMaxStackSize() - existing.getAmount();
            if (room <= 0) {
                continue;
            }
            int moved = Math.min(room, incoming.getAmount());
            existing.setAmount(existing.getAmount() + moved);
            incoming.setAmount(incoming.getAmount() - moved);
            if (incoming.getAmount() <= 0) {
                return accepted;
            }
        }
        while (incoming.getAmount() > incoming.getMaxStackSize()) {
            ItemStack split = incoming.clone();
            split.setAmount(incoming.getMaxStackSize());
            stock.add(split);
            incoming.setAmount(incoming.getAmount() - incoming.getMaxStackSize());
        }
        if (incoming.getAmount() > 0) {
            stock.add(incoming);
        }
        return accepted;
    }

    /**
     * Takes up to {@code amount} of the item out of the stock.
     *
     * @return what could actually be taken, or empty when the stock has none
     */
    public Optional<ItemStack> take(ItemStack template, int amount) {
        int wanted = Math.max(1, amount);
        int taken = 0;
        for (int index = 0; index < stock.size() && taken < wanted; index++) {
            ItemStack item = stock.get(index);
            if (!item.isSimilar(template)) {
                continue;
            }
            int moved = Math.min(wanted - taken, item.getAmount());
            taken += moved;
            if (item.getAmount() - moved <= 0) {
                stock.remove(index--);
            } else {
                item.setAmount(item.getAmount() - moved);
            }
        }
        if (taken <= 0) {
            return Optional.empty();
        }
        ItemStack handed = template.clone();
        handed.setAmount(taken);
        return Optional.of(handed);
    }

    public ItemStack withdrawItem(int index) {
        if (index < 0 || index >= stock.size()) {
            return null;
        }
        return stock.remove(index);
    }

    public List<ItemStack> withdrawAll() {
        List<ItemStack> withdrawn = new ArrayList<>(stock);
        stock.clear();
        return withdrawn;
    }

    public void restoreStock(List<ItemStack> items) {
        stock.clear();
        if (items == null) {
            return;
        }
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                stock.add(item.clone());
            }
        }
    }
}
