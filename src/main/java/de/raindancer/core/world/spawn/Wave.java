package de.raindancer.core.world.spawn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a wave is: several packs, each after a delay.
 *
 * <h2>Why a wave is a plan rather than a loop</h2>
 * Because the whole difference between "a wave" and "a hundred zombies" is time. Everything at once
 * is a lag spike and a death; the same creatures in five packs thirty seconds apart is a fight
 * somebody can win, and it can be stopped halfway through if it turns out to be a mistake.
 *
 * <p>The plan being a value — no server, no scheduler, no entities — is what makes both of those
 * testable: what the third pack contains, how long the whole thing lasts, and that a wave which was
 * cancelled is a wave whose remaining packs never existed.
 */
public record Wave(List<Pack> packs) {

    /** Enough that a wave can be long; few enough that one cannot run until the server restarts. */
    public static final int MOST_PACKS = 20;

    /** Per pack. A hundred at once is not a wave, it is a crash. */
    public static final int MOST_PER_PACK = 40;

    public Wave {
        packs = List.copyOf(packs);
    }

    /**
     * One arrival: what turns up, how far out, and how long after the wave started.
     *
     * @param creatures entity-type names, one entry per creature — so a pack of three zombies and one
     *                  skeleton is four entries. A count per kind would have to be expanded before it
     *                  could be placed, and expanding it here means the ring never has to know
     * @param afterTicks from the start of the wave, not from the pack before it: a plan whose timings
     *                   are relative cannot be read without adding them all up, and a pack that fails
     *                   would shift every one after it
     */
    public record Pack(List<String> creatures, int radius, long afterTicks) {

        public Pack {
            creatures = List.copyOf(creatures);
        }

        public int size() {
            return creatures.size();
        }
    }

    /** How many creatures the whole wave brings. */
    public int total() {
        return packs.stream().mapToInt(Pack::size).sum();
    }

    /** How long from the first pack to the last, in ticks. */
    public long lengthTicks() {
        return packs.stream().mapToLong(Pack::afterTicks).max().orElse(0L);
    }

    /** Every kind in the wave, with how many of each — for a screen that describes it. */
    public Map<String, Integer> breakdown() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Pack pack : packs) {
            for (String creature : pack.creatures()) {
                counts.merge(creature.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        return Map.copyOf(counts);
    }

    /**
     * A wave of {@code packs} arrivals, each of {@code perPack} of the given kinds, {@code everyTicks}
     * apart.
     *
     * <p>The kinds are dealt round-robin into each pack, so a pack of five from two kinds is three of
     * one and two of the other rather than five of whichever was named first.
     *
     * <p>Everything is clamped rather than refused. These numbers arrive from a screen where somebody
     * held a button down, and a wave of six hundred is not a request — it is a slip, and the useful
     * answer is the biggest wave this is willing to run.
     */
    public static Wave of(List<String> kinds, int packs, int perPack, int radius, long everyTicks) {
        if (kinds == null || kinds.isEmpty()) {
            return new Wave(List.of());
        }
        int howMany = Math.max(1, Math.min(MOST_PACKS, packs));
        int each = Math.max(1, Math.min(MOST_PER_PACK, perPack));
        long gap = Math.max(0L, everyTicks);

        List<Pack> built = new ArrayList<>(howMany);
        int dealt = 0;
        for (int index = 0; index < howMany; index++) {
            List<String> creatures = new ArrayList<>(each);
            for (int one = 0; one < each; one++) {
                creatures.add(kinds.get(dealt++ % kinds.size()));
            }
            built.add(new Pack(creatures, radius, gap * index));
        }
        return new Wave(built);
    }

    /** A single pack, which is the common case and reads badly as "a wave of one". */
    public static Wave justOne(List<String> kinds, int howMany, int radius) {
        return of(kinds, 1, howMany, radius, 0L);
    }
}
