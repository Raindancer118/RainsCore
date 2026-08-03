package de.raindancer.core.loot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * A pool of things that might come out of a container, and how full it is.
 *
 * <h2>Why the randomness is a parameter</h2>
 * A loot table is a probability distribution and nothing else, so "it seemed about right when I
 * opened twenty chests" is not a test. Every roll here takes the {@link Random} it should use, which
 * means the weighting can be checked exactly — including the boundaries, which is where a weighted
 * pick is always wrong: an off-by-one that makes the rarest entry unobtainable is invisible for
 * months.
 *
 * <h2>Tiers</h2>
 * The thing the config this is modelled on could not express. A supply drop is not a chest with
 * different odds; it is a better chest. Saying that once beats copying a pool and editing the
 * numbers, which is how two tables drift until nobody knows which is authoritative.
 *
 * @param fillPercent how much of a container to fill, as a percentage of its slots
 * @param tier        how good it is; 1 is ordinary
 */
public record LootTable(String plugin, String id, int tier, int fillPercent,
                        List<LootEntry> entries) {

    public LootTable {
        if (plugin == null || plugin.isBlank()) {
            throw new IllegalArgumentException("A loot table must say which plugin defines it.");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A loot table needs an id.");
        }
        plugin = plugin.trim().toLowerCase(Locale.ROOT);
        id = id.trim().toLowerCase(Locale.ROOT);
        tier = Math.max(1, tier);
        fillPercent = Math.max(0, Math.min(100, fillPercent));
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static Builder builder(String plugin, String id) {
        return new Builder(plugin, id);
    }

    /** {@code hg:chest} — unique across the server. */
    public String key() {
        return plugin + ":" + id;
    }

    /** Everything in the pool added up. Zero means nothing will ever come out. */
    public int totalWeight() {
        return entries.stream().mapToInt(LootEntry::weight).sum();
    }

    // ---------------------------------------------------------------------------- picking

    /**
     * One entry, chosen by weight.
     *
     * <p>The usual cumulative walk, and the reason it is worth its own tests: a roll of zero has to
     * land in the first entry and the highest possible roll has to land in the last. Getting the
     * second wrong is how the rarest item in a pool becomes unobtainable.
     */
    public Optional<LootEntry> pick(Random random) {
        int total = totalWeight();
        if (total <= 0) {
            return Optional.empty();
        }
        int roll = random.nextInt(total);
        int seen = 0;
        for (LootEntry entry : entries) {
            if (entry.weight() <= 0) {
                continue;
            }
            seen += entry.weight();
            if (roll < seen) {
                return Optional.of(entry);
            }
        }
        // Unreachable while the weights add up, but a table changed underneath a roll should give
        // something rather than nothing.
        return entries.stream().filter(entry -> entry.weight() > 0).reduce((first, last) -> last);
    }

    // ---------------------------------------------------------------------------- filling

    /**
     * How many slots of a container this table fills.
     *
     * <p>Never zero: an empty chest reads as a bug rather than as bad luck, and a player who finds
     * three in a row concludes the plugin is broken. Never more than the container holds either.
     */
    public int slotsToFill(int containerSize) {
        int wanted = containerSize * fillPercent / 100;
        return Math.max(1, Math.min(containerSize, wanted));
    }

    /**
     * Which slots to put things in — distinct, so nothing is written over.
     *
     * <p>By shuffling the slots rather than picking at random until enough distinct ones turn up:
     * the second is what the version this replaces did, with a hundred-attempt escape hatch, and it
     * quietly puts fewer items in a nearly-full container than it was asked to.
     */
    public static List<Integer> chooseSlots(int containerSize, int howMany, Random random) {
        List<Integer> slots = new ArrayList<>(containerSize);
        for (int slot = 0; slot < containerSize; slot++) {
            slots.add(slot);
        }
        java.util.Collections.shuffle(slots, random);
        return List.copyOf(slots.subList(0, Math.max(0, Math.min(containerSize, howMany))));
    }

    /**
     * A whole container's worth: what goes where, and how much of it.
     *
     * <p>Values rather than {@code ItemStack}s, so this is testable; {@code LootFiller} turns them
     * into real items.
     */
    public List<LootRoll> roll(int containerSize, Random random) {
        List<LootRoll> rolled = new ArrayList<>();
        for (int slot : chooseSlots(containerSize, slotsToFill(containerSize), random)) {
            pick(random).ifPresent(entry ->
                    rolled.add(new LootRoll(slot, entry, entry.rollAmount(random))));
        }
        return List.copyOf(rolled);
    }

    /** Every custom item this table can give, so a plugin can check they all exist. */
    public Set<String> customItems() {
        Set<String> keys = new LinkedHashSet<>();
        for (LootEntry entry : entries) {
            entry.customItem().ifPresent(keys::add);
        }
        return Set.copyOf(keys);
    }

    public static final class Builder {
        private final String plugin;
        private final String id;
        private int tier = 1;
        private int fillPercent = 30;
        private final List<LootEntry> entries = new ArrayList<>();

        private Builder(String plugin, String id) {
            this.plugin = plugin;
            this.id = id;
        }

        /** How good this table is. 1 is ordinary; a supply drop is higher. */
        public Builder tier(int value) {
            this.tier = value;
            return this;
        }

        /** How much of a container to fill, as a percentage of its slots. */
        public Builder fillPercent(int value) {
            this.fillPercent = value;
            return this;
        }

        public Builder entry(LootEntry value) {
            if (value != null) {
                entries.add(value);
            }
            return this;
        }

        public LootTable build() {
            return new LootTable(plugin, id, tier, fillPercent, entries);
        }
    }
}
