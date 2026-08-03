package de.raindancer.core.land;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The claim's potion supply, used as fuel when the server requires real potions for claim effects.
 * <p>
 * Kept apart from {@link ClaimPantry}: the pantry is food eaten by individual players, this is a stock of
 * brewed potions burnt by the claim itself. Stored as whole {@link ItemStack}s so an owner can take
 * unused potions back out, and so a custom-brewed potion keeps its identity.
 */
public final class PotionStore {

    /** Deposit order is burn order: the queue is worked through front to back. */
    private final List<ItemStack> potions = new ArrayList<>();

    /** The potion currently burning, so the GUI can show what is running and why. */
    private ItemStack activeBrew;
    private final List<PotionEffect> activeEffects = new ArrayList<>();
    /** Epoch millis at which the active brew runs out; {@link Long#MAX_VALUE} means never. */
    private long activeUntil;

    public List<ItemStack> potions() {
        return Collections.unmodifiableList(potions);
    }

    public boolean isEmpty() {
        return potions.isEmpty();
    }

    public int totalPotions() {
        int total = 0;
        for (ItemStack stack : potions) {
            total += stack.getAmount();
        }
        return total;
    }

    /** True for drinkable, splash and lingering potions that actually carry an effect. */
    public static boolean isPotion(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        Material type = stack.getType();
        if (type != Material.POTION && type != Material.SPLASH_POTION
                && type != Material.LINGERING_POTION) {
            return false;
        }
        return !effectsOf(stack).isEmpty();
    }

    /**
     * The effect types a potion grants — its base type plus any custom effects.
     * <p>
     * Reading both means a hand-brewed or plugin-made potion is matched by what it actually does rather
     * than by its name.
     */
    public static List<PotionEffectType> effectsOf(ItemStack stack) {
        List<PotionEffectType> types = new ArrayList<>();
        if (stack == null || !(stack.getItemMeta() instanceof PotionMeta meta)) {
            return types;
        }
        if (meta.hasBasePotionType() && meta.getBasePotionType() != null) {
            for (PotionEffect effect : meta.getBasePotionType().getPotionEffects()) {
                if (!types.contains(effect.getType())) {
                    types.add(effect.getType());
                }
            }
        }
        if (meta.hasCustomEffects()) {
            for (PotionEffect effect : meta.getCustomEffects()) {
                if (!types.contains(effect.getType())) {
                    types.add(effect.getType());
                }
            }
        }
        return types;
    }

    /** Whether the store holds at least one potion granting this effect. */
    public boolean has(PotionEffectType type) {
        return countFor(type) > 0;
    }

    public int countFor(PotionEffectType type) {
        int count = 0;
        for (ItemStack stack : potions) {
            if (effectsOf(stack).contains(type)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** Adds potions, merging into existing stacks. Returns how many were accepted. */
    public int deposit(ItemStack stack) {
        if (!isPotion(stack)) {
            return 0;
        }
        int accepted = stack.getAmount();
        ItemStack incoming = stack.clone();
        for (ItemStack existing : potions) {
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
            potions.add(split);
            incoming.setAmount(incoming.getAmount() - incoming.getMaxStackSize());
        }
        if (incoming.getAmount() > 0) {
            potions.add(incoming);
        }
        return accepted;
    }

    /**
     * Burns exactly one potion granting the effect.
     *
     * @return the consumed potion, or empty when none was in stock
     */
    public Optional<ItemStack> consumeOne(PotionEffectType type) {
        for (int index = 0; index < potions.size(); index++) {
            ItemStack stack = potions.get(index);
            if (!effectsOf(stack).contains(type)) {
                continue;
            }
            ItemStack single = stack.clone();
            single.setAmount(1);
            if (stack.getAmount() <= 1) {
                potions.remove(index);
            } else {
                stack.setAmount(stack.getAmount() - 1);
            }
            return Optional.of(single);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------ the burning queue

    /** Takes the next potion off the front of the queue, keeping deposit order. */
    public Optional<ItemStack> pollNext() {
        if (potions.isEmpty()) {
            return Optional.empty();
        }
        ItemStack first = potions.get(0);
        ItemStack single = first.clone();
        single.setAmount(1);
        if (first.getAmount() <= 1) {
            potions.remove(0);
        } else {
            first.setAmount(first.getAmount() - 1);
        }
        return Optional.of(single);
    }

    /** The potion that would burn next, without taking it. */
    public Optional<ItemStack> peekNext() {
        return potions.isEmpty() ? Optional.empty() : Optional.of(potions.get(0));
    }

    /**
     * Starts burning a potion.
     *
     * @param until epoch millis at which it lapses, or {@link Long#MAX_VALUE} to run indefinitely
     */
    public void activate(ItemStack brew, List<PotionEffect> effects, long until) {
        this.activeBrew = brew == null ? null : brew.clone();
        this.activeEffects.clear();
        if (effects != null) {
            this.activeEffects.addAll(effects);
        }
        this.activeUntil = until;
    }

    public void clearActive() {
        activeBrew = null;
        activeEffects.clear();
        activeUntil = 0L;
    }

    /** True while a potion is burning and has not lapsed. */
    public boolean isActive() {
        return !activeEffects.isEmpty()
                && (activeUntil == Long.MAX_VALUE || System.currentTimeMillis() < activeUntil);
    }

    public List<PotionEffect> activeEffects() {
        return Collections.unmodifiableList(activeEffects);
    }

    public ItemStack activeBrew() {
        return activeBrew == null ? null : activeBrew.clone();
    }

    public long activeUntil() {
        return activeUntil;
    }

    /** Milliseconds left on the active brew; {@link Long#MAX_VALUE} when it never lapses. */
    public long activeRemaining() {
        if (!isActive()) {
            return 0L;
        }
        return activeUntil == Long.MAX_VALUE
                ? Long.MAX_VALUE : Math.max(0L, activeUntil - System.currentTimeMillis());
    }

    /** The effects the whole queue would grant, in the order they will be burnt. */
    public List<PotionEffectType> queuedEffectTypes() {
        List<PotionEffectType> types = new ArrayList<>();
        for (ItemStack stack : potions) {
            for (PotionEffectType type : effectsOf(stack)) {
                if (!types.contains(type)) {
                    types.add(type);
                }
            }
        }
        return types;
    }

    public ItemStack withdrawItem(int index) {
        if (index < 0 || index >= potions.size()) {
            return null;
        }
        return potions.remove(index);
    }

    public List<ItemStack> withdrawAll() {
        List<ItemStack> withdrawn = new ArrayList<>(potions);
        potions.clear();
        return withdrawn;
    }

    public void restore(List<ItemStack> stacks) {
        potions.clear();
        if (stacks == null) {
            return;
        }
        for (ItemStack stack : stacks) {
            if (isPotion(stack)) {
                potions.add(stack.clone());
            }
        }
    }

    /**
     * Restores the potion that was burning when the server stopped.
     * <p>
     * Without this a restart would throw away a partly burnt potion and immediately draw the next one.
     */
    public void restoreActive(ItemStack brew, long until) {
        if (brew == null || !isPotion(brew)) {
            clearActive();
            return;
        }
        List<PotionEffect> effects = new ArrayList<>();
        if (brew.getItemMeta() instanceof PotionMeta meta) {
            if (meta.hasBasePotionType() && meta.getBasePotionType() != null) {
                effects.addAll(meta.getBasePotionType().getPotionEffects());
            }
            if (meta.hasCustomEffects()) {
                effects.addAll(meta.getCustomEffects());
            }
        }
        activate(brew, effects, until);
    }

    /** Every effect a potion grants, with the potion's own amplifiers. */
    public static List<PotionEffect> potionEffectsOf(ItemStack stack) {
        List<PotionEffect> effects = new ArrayList<>();
        if (stack == null || !(stack.getItemMeta() instanceof PotionMeta meta)) {
            return effects;
        }
        if (meta.hasBasePotionType() && meta.getBasePotionType() != null) {
            effects.addAll(meta.getBasePotionType().getPotionEffects());
        }
        if (meta.hasCustomEffects()) {
            effects.addAll(meta.getCustomEffects());
        }
        return effects;
    }
}
