package de.raindancer.core.land;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.FoodProperties;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The shared food store of a claim.
 * <p>
 * Anybody allowed to may drop food in; players inside the claim are then fed from it automatically when
 * they get hungry. Kept apart from {@link ClaimBank} on purpose: the bank is the owner's earnings and
 * paying it out is a deliberate act, while the pantry is a communal larder that is consumed by design.
 * <p>
 * Nutrition comes from the item's own {@code FOOD} data component, so modded or custom food with an
 * edited component is valued correctly instead of being guessed from the material name.
 */
public final class ClaimPantry {

    /** Whether hungry players inside the claim are fed automatically. */
    private boolean enabled;
    /** Feed a player once their food level drops to this or below (out of 20). */
    private int threshold = 16;
    /** Whether visitors may contribute food themselves. */
    private boolean allowDeposits = true;

    private final List<ItemStack> items = new ArrayList<>();

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int threshold() {
        return threshold;
    }

    public void threshold(int threshold) {
        this.threshold = Math.max(1, Math.min(19, threshold));
    }

    public boolean allowDeposits() {
        return allowDeposits;
    }

    public void allowDeposits(boolean allowDeposits) {
        this.allowDeposits = allowDeposits;
    }

    public List<ItemStack> items() {
        return Collections.unmodifiableList(items);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int totalItems() {
        int total = 0;
        for (ItemStack stack : items) {
            total += stack.getAmount();
        }
        return total;
    }

    /** Total hunger points the pantry can currently restore. */
    public int totalNutrition() {
        int total = 0;
        for (ItemStack stack : items) {
            total += nutritionOf(stack) * stack.getAmount();
        }
        return total;
    }

    /** True when the stack carries a food component and can therefore be stored here. */
    public static boolean isFood(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return stack.hasData(DataComponentTypes.FOOD);
    }

    /** Hunger points a single item of this stack restores, or {@code 0} when it is not food. */
    public static int nutritionOf(ItemStack stack) {
        if (!isFood(stack)) {
            return 0;
        }
        FoodProperties food = stack.getData(DataComponentTypes.FOOD);
        return food == null ? 0 : food.nutrition();
    }

    public static float saturationOf(ItemStack stack) {
        if (!isFood(stack)) {
            return 0f;
        }
        FoodProperties food = stack.getData(DataComponentTypes.FOOD);
        return food == null ? 0f : food.saturation();
    }

    /** Adds food, merging into existing stacks. Returns how many items were accepted. */
    public int deposit(ItemStack stack) {
        if (!isFood(stack)) {
            return 0;
        }
        int accepted = stack.getAmount();
        ItemStack incoming = stack.clone();
        for (ItemStack existing : items) {
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
            items.add(split);
            incoming.setAmount(incoming.getAmount() - incoming.getMaxStackSize());
        }
        if (incoming.getAmount() > 0) {
            items.add(incoming);
        }
        return accepted;
    }

    /**
     * Takes one item of the best-suited food for a player missing {@code missing} hunger points.
     * <p>
     * Prefers the largest item that still fits the gap, so a golden apple is not burned to top somebody
     * up by one point; if nothing fits, the smallest available item is used.
     */
    public ItemStack takeBestFor(int missing) {
        int bestIndex = -1;
        int bestNutrition = -1;
        int smallestIndex = -1;
        int smallestNutrition = Integer.MAX_VALUE;

        for (int index = 0; index < items.size(); index++) {
            int nutrition = nutritionOf(items.get(index));
            if (nutrition <= 0) {
                continue;
            }
            if (nutrition <= missing && nutrition > bestNutrition) {
                bestNutrition = nutrition;
                bestIndex = index;
            }
            if (nutrition < smallestNutrition) {
                smallestNutrition = nutrition;
                smallestIndex = index;
            }
        }

        int chosen = bestIndex >= 0 ? bestIndex : smallestIndex;
        if (chosen < 0) {
            return null;
        }
        ItemStack source = items.get(chosen);
        ItemStack single = source.clone();
        single.setAmount(1);
        if (source.getAmount() <= 1) {
            items.remove(chosen);
        } else {
            source.setAmount(source.getAmount() - 1);
        }
        return single;
    }

    /** Removes and returns a whole stack by index, used when an owner empties the pantry. */
    public ItemStack withdrawItem(int index) {
        if (index < 0 || index >= items.size()) {
            return null;
        }
        return items.remove(index);
    }

    public List<ItemStack> withdrawAll() {
        List<ItemStack> withdrawn = new ArrayList<>(items);
        items.clear();
        return withdrawn;
    }

    public void restore(List<ItemStack> stacks) {
        items.clear();
        if (stacks == null) {
            return;
        }
        for (ItemStack stack : stacks) {
            if (isFood(stack)) {
                items.add(stack.clone());
            }
        }
    }
}
