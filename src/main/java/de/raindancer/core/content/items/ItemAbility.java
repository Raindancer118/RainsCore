package de.raindancer.core.content.items;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What a custom item does, and the rules about when it may do it.
 *
 * <h2>Modelled on what these items actually are</h2>
 * The Hunger Games plugin has thirteen of them — a rod that calls a storm on whatever you are
 * looking at, boots that let you fly for four seconds, a shell that saves you once from your own
 * stupidity. Their effects have nothing in common; their <em>shape</em> has everything in common. A
 * trigger, a cooldown, a number of charges, and a boolean for "should the item now vanish". So the
 * shape lives here and the effect stays the plugin's.
 *
 * <h2>Why the effect is a predicate</h2>
 * {@link Builder#attempts} returns whether it actually happened. A grappling hook aimed at the sky
 * has nothing to grapple to, and charging somebody a use and a thirty-second cooldown for a shot
 * that never went anywhere is the sort of thing that makes an item feel broken. {@link Builder#does}
 * is the shorthand for an ability that always succeeds.
 *
 * <h2>Charges and consuming the item are different questions</h2>
 * {@link #maxCharges} limits how often <em>a player</em> may ever use it; {@link #consumesItem} takes
 * one physical item out of their hand every time it works. An item whose lore says "single use" wants
 * the second: a player handed a second medikit by a sponsor has to be able to use it, and a per-player
 * charge of one says they may not — the first one they ever used spent the only charge they will get,
 * and every medikit after that is a melon slice.
 *
 * @param cooldownMillis how long before the same player may use it again; null for no cooldown
 * @param maxCharges     how many times, ever; null for unlimited
 * @param consumesItem   whether one is taken from the stack each time it actually works
 */
public record ItemAbility(String plugin, String id, ItemTrigger trigger, String description,
                          Long cooldownMillis, Integer maxCharges, boolean consumesItem,
                          Predicate<ItemUse> effect) {

    public ItemAbility {
        if (plugin == null || plugin.isBlank()) {
            throw new IllegalArgumentException("An ability must say which plugin defines it.");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("An ability needs an id.");
        }
        if (effect == null) {
            throw new IllegalArgumentException(
                    "An ability with no effect is a button that does nothing.");
        }
        plugin = plugin.trim().toLowerCase(Locale.ROOT);
        id = id.trim().toLowerCase(Locale.ROOT);
        trigger = trigger == null ? ItemTrigger.RIGHT_CLICK : trigger;
        description = description == null ? "" : description.trim();
    }

    public static Builder builder(String plugin, String id) {
        return new Builder(plugin, id);
    }

    /** {@code hg:lightning} — unique across the server. */
    public String key() {
        return plugin + ":" + id;
    }

    public Optional<Duration> cooldown() {
        return Optional.ofNullable(cooldownMillis).map(Duration::ofMillis);
    }

    public Optional<Integer> charges() {
        return Optional.ofNullable(maxCharges);
    }

    public static final class Builder {
        private final String plugin;
        private final String id;
        private ItemTrigger trigger = ItemTrigger.RIGHT_CLICK;
        private String description;
        private Long cooldownMillis;
        private Integer maxCharges;
        private boolean consumesItem;
        private Predicate<ItemUse> effect;

        private Builder(String plugin, String id) {
            this.plugin = plugin;
            this.id = id;
        }

        public Builder on(ItemTrigger value) {
            this.trigger = value;
            return this;
        }

        /** One line saying what it does, for the item's lore and for a command. */
        public Builder describedAs(String value) {
            this.description = value;
            return this;
        }

        public Builder cooldown(Duration value) {
            this.cooldownMillis = value == null || value.isNegative() || value.isZero()
                    ? null : value.toMillis();
            return this;
        }

        /** How many times one player may ever use it. Left off, it never runs out. */
        public Builder charges(int value) {
            this.maxCharges = value < 1 ? null : value;
            return this;
        }

        /**
         * One of these is taken out of the holder's hand every time the effect actually works.
         *
         * <p>What "single use" means for a thing you are given rather than a thing you learn. Not
         * {@code charges(1)}: that is a limit on the player, so the second medikit a sponsor sends is
         * refused because the first one was used — the sort of failure that is reported as "the shop
         * sold me a broken item".
         */
        public Builder consumesItem() {
            this.consumesItem = true;
            return this;
        }

        /** An effect that always happens. */
        public Builder does(java.util.function.Consumer<ItemUse> value) {
            this.effect = use -> {
                value.accept(use);
                return true;
            };
            return this;
        }

        /**
         * An effect that may not happen — and says so, so a miss costs neither a charge nor a
         * cooldown.
         */
        public Builder attempts(Predicate<ItemUse> value) {
            this.effect = value;
            return this;
        }

        public ItemAbility build() {
            return new ItemAbility(plugin, id, trigger, description, cooldownMillis, maxCharges,
                    consumesItem, effect);
        }
    }
}
