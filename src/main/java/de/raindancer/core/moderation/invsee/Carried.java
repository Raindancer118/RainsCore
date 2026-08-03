package de.raindancer.core.moderation.invsee;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * What somebody is carrying, as a value.
 *
 * <h2>Why a snapshot rather than a live inventory</h2>
 * Because the alternative is holding a reference to a player while a window is open, and a player
 * who logs out mid-view leaves that reference pointing at somebody the server has forgotten. A
 * snapshot also makes the offline case the same shape as the online one: one is read from an
 * inventory, the other out of a file on disk, and everything above them cannot tell which.
 *
 * <p>Changes make a new snapshot rather than editing this one. A view that mutates as you look at it
 * cannot be read-only however carefully the click handler is written, and read-only is the level
 * moderators use most.
 *
 * <h2>Why it is generic</h2>
 * The type of an item is the server's business, not this class's. Held loosely, the whole of the
 * shape — which slots exist, where armour goes, what counts as empty — is testable without a server,
 * and the same class carries the raw bytes read out of a player file on the way to becoming items.
 *
 * @param <T> what an item is here: the server's item type, or the bytes one was stored as
 */
public final class Carried<T> {

    private final Map<Section, List<T>> parts;

    private Carried(Map<Section, List<T>> parts) {
        this.parts = parts;
    }

    /** Nothing in any part — the starting point, and what an unknown player has. */
    public static <T> Carried<T> empty() {
        Map<Section, List<T>> parts = new EnumMap<>(Section.class);
        for (Section section : Section.values()) {
            parts.put(section, nulls(section.size()));
        }
        return new Carried<>(parts);
    }

    private static <T> List<T> nulls(int size) {
        List<T> slots = new ArrayList<>(size);
        for (int at = 0; at < size; at++) {
            slots.add(null);
        }
        return slots;
    }

    /**
     * A snapshot of one part, taken from an array that may be short, long, or full of nulls.
     *
     * <p>Lenient on purpose: the arrays these come from are the server's, and a server that one day
     * hands back forty-one slots instead of forty must not be an exception thrown at a moderator.
     */
    public Carried<T> withAll(Section section, List<T> items) {
        if (section == null || items == null) {
            return this;
        }
        Map<Section, List<T>> copy = new EnumMap<>(parts);
        List<T> slots = nulls(section.size());
        for (int at = 0; at < Math.min(items.size(), section.size()); at++) {
            slots.set(at, items.get(at));
        }
        copy.put(section, slots);
        return new Carried<>(copy);
    }

    /** One item put in one place. */
    public Carried<T> with(Section section, int indexWithin, T item) {
        if (!holds(section, indexWithin)) {
            // Refused rather than ignored: silently dropping a write is how an item disappears with
            // nobody at fault.
            return this;
        }
        Map<Section, List<T>> copy = new EnumMap<>(parts);
        List<T> slots = new ArrayList<>(parts.get(section));
        slots.set(indexWithin, item);
        copy.put(section, slots);
        return new Carried<>(copy);
    }

    /** What is in one place, or null for an empty slot and for a slot that does not exist. */
    public T at(Section section, int indexWithin) {
        return holds(section, indexWithin) ? parts.get(section).get(indexWithin) : null;
    }

    /** One part in order, empty slots included as nulls. */
    public List<T> allOf(Section section) {
        return section == null ? List.of() : Collections.unmodifiableList(parts.get(section));
    }

    public int sizeOf(Section section) {
        return section == null ? 0 : section.size();
    }

    /** How many slots of one part have something in them. */
    public int countIn(Section section) {
        if (section == null) {
            return 0;
        }
        return (int) parts.get(section).stream().filter(Objects::nonNull).count();
    }

    /** How many slots in total have something in them. */
    public int count() {
        int total = 0;
        for (Section section : Section.values()) {
            total += countIn(section);
        }
        return total;
    }

    public boolean isEmpty() {
        return count() == 0;
    }

    /**
     * The same snapshot with every item put through one conversion — bytes into items, items into
     * bytes.
     *
     * <p>Empty slots stay empty without the conversion being asked about them, which is what keeps
     * the two sides of it from each having to answer for null.
     */
    public <R> Carried<R> map(Function<T, R> into) {
        Map<Section, List<R>> converted = new EnumMap<>(Section.class);
        for (Section section : Section.values()) {
            List<T> slots = parts.get(section);
            List<R> made = nulls(section.size());
            for (int at = 0; at < slots.size(); at++) {
                T item = slots.get(at);
                if (item != null) {
                    made.set(at, into.apply(item));
                }
            }
            converted.put(section, made);
        }
        return new Carried<>(converted);
    }

    private boolean holds(Section section, int indexWithin) {
        return section != null && indexWithin >= 0 && indexWithin < section.size();
    }

    /**
     * Two snapshots are the same when the same things are in the same places.
     *
     * <p>Compared deeply, because one of the two things carried here is raw bytes: an item read out
     * of a player file is a {@code byte[]}, and arrays compare by identity, so the obvious
     * implementation would report every round trip as a difference and every real difference as one
     * too. That is a test that can only ever be passed by accident.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Carried<?> carried)) {
            return false;
        }
        for (Section section : Section.values()) {
            List<?> mine = parts.get(section);
            List<?> theirs = carried.parts.get(section);
            for (int at = 0; at < mine.size(); at++) {
                if (!Objects.deepEquals(mine.get(at), theirs.get(at))) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        for (Section section : Section.values()) {
            for (Object item : parts.get(section)) {
                // Wrapped so that arrays are hashed by what is in them, matching equals above.
                hash = 31 * hash + Arrays.deepHashCode(new Object[] {item});
            }
        }
        return hash;
    }

    @Override
    public String toString() {
        StringBuilder said = new StringBuilder("Carried[");
        for (Section section : Section.values()) {
            said.append(section.title()).append('=').append(countIn(section)).append(' ');
        }
        return said.append(']').toString();
    }
}
