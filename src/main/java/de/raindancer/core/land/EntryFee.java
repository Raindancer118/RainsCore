package de.raindancer.core.land;

import org.bukkit.inventory.ItemStack;

/**
 * The toll a visitor pays to step into a claim.
 * <p>
 * Only meaningful when the server admin has switched entry fees on globally; the claim owner then
 * decides currency and amount. Collected fees land in the {@link ClaimBank} so offline owners do not
 * lose anything.
 */
public final class EntryFee {

    private boolean enabled;
    private CostType type = CostType.NONE;
    private int amount = 1;
    private ItemStack item;
    /** How long a paid entry stays valid, in seconds. {@code 0} charges on every crossing. */
    private int passDurationSeconds = 300;

    public boolean enabled() {
        return enabled && type != CostType.NONE && amount > 0
                && (type != CostType.ITEM || item != null);
    }

    public boolean rawEnabled() {
        return enabled;
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public CostType type() {
        return type;
    }

    public void type(CostType type) {
        this.type = type == null ? CostType.NONE : type;
    }

    public int amount() {
        return amount;
    }

    public void amount(int amount) {
        this.amount = Math.max(1, amount);
    }

    public ItemStack item() {
        return item == null ? null : item.clone();
    }

    public void item(ItemStack item) {
        this.item = item == null ? null : item.clone();
    }

    public int passDurationSeconds() {
        return passDurationSeconds;
    }

    public void passDurationSeconds(int seconds) {
        this.passDurationSeconds = Math.max(0, seconds);
    }

    public EntryFee copy() {
        EntryFee copy = new EntryFee();
        copy.enabled = enabled;
        copy.type = type;
        copy.amount = amount;
        copy.item = item == null ? null : item.clone();
        copy.passDurationSeconds = passDurationSeconds;
        return copy;
    }
}
