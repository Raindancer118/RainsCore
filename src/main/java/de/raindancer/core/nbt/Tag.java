package de.raindancer.core.nbt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One value in Minecraft's own save format.
 *
 * <h2>Why this exists at all</h2>
 * Because the only way to see what an offline player is carrying is to read the file the server
 * wrote them into, and that file is NBT. The alternative is reaching into the server's internals for
 * its own reader, which breaks on the update after next; this is about two hundred lines and the
 * format has not changed since 2011.
 *
 * <p>What this deliberately does <em>not</em> do is understand items. An item compound is passed
 * back to the server untouched, which is what keeps this from having an opinion about a format that
 * genuinely does change — see {@code PlayerDataCodec}.
 *
 * <p>Values are immutable. The arrays are copied on the way in and on the way out, because a
 * "snapshot" that shares its array with the file it came from is not one.
 */
public sealed interface Tag {

    int END = 0;
    int BYTE = 1;
    int SHORT = 2;
    int INT = 3;
    int LONG = 4;
    int FLOAT = 5;
    int DOUBLE = 6;
    int BYTE_ARRAY = 7;
    int STRING = 8;
    int LIST = 9;
    int COMPOUND = 10;
    int INT_ARRAY = 11;
    int LONG_ARRAY = 12;

    /** Which of the twelve kinds this is — the byte written in front of it. */
    int id();

    record Byte(byte value) implements Tag {
        @Override
        public int id() {
            return BYTE;
        }
    }

    record Short(short value) implements Tag {
        @Override
        public int id() {
            return SHORT;
        }
    }

    record Int(int value) implements Tag {
        @Override
        public int id() {
            return INT;
        }
    }

    record Long(long value) implements Tag {
        @Override
        public int id() {
            return LONG;
        }
    }

    record Float(float value) implements Tag {
        @Override
        public int id() {
            return FLOAT;
        }
    }

    record Double(double value) implements Tag {
        @Override
        public int id() {
            return DOUBLE;
        }
    }

    record ByteArray(byte[] value) implements Tag {
        public ByteArray {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public int id() {
            return BYTE_ARRAY;
        }

        // A record's own equals compares arrays by identity, which would make every round-trip test
        // pass or fail for the wrong reason.
        @Override
        public boolean equals(Object other) {
            return other instanceof ByteArray that && Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "ByteArray[" + value.length + " bytes]";
        }
    }

    record Str(String value) implements Tag {
        @Override
        public int id() {
            return STRING;
        }
    }

    /**
     * A list, every entry the same kind.
     *
     * @param elementType what kind that is; {@link #END} for an empty list, as the format requires
     */
    record List_(int elementType, List<Tag> items) implements Tag {
        public List_ {
            items = java.util.List.copyOf(items);
        }

        /** A list that works out its own element type — empty means the format's "end" marker. */
        public static List_ of(List<Tag> items) {
            return new List_(items.isEmpty() ? END : items.get(0).id(), items);
        }

        public static List_ empty() {
            return new List_(END, java.util.List.of());
        }

        @Override
        public int id() {
            return LIST;
        }

        public int size() {
            return items.size();
        }
    }

    /** A compound: names to values, in the order they were written. */
    record Compound(Map<String, Tag> values) implements Tag {
        public Compound {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public static Compound empty() {
            return new Compound(Map.of());
        }

        @Override
        public int id() {
            return COMPOUND;
        }

        public Optional<Tag> get(String name) {
            return Optional.ofNullable(values.get(name));
        }

        public boolean has(String name) {
            return values.containsKey(name);
        }

        public Optional<Compound> compound(String name) {
            return get(name).filter(Compound.class::isInstance).map(Compound.class::cast);
        }

        public Optional<List_> list(String name) {
            return get(name).filter(List_.class::isInstance).map(List_.class::cast);
        }

        /** A number, whatever width it was stored as — the format is not consistent about that. */
        public Optional<java.lang.Long> number(String name) {
            java.lang.Long number = switch (values.get(name)) {
                case Byte value -> java.lang.Long.valueOf(value.value());
                case Short value -> java.lang.Long.valueOf(value.value());
                case Int value -> java.lang.Long.valueOf(value.value());
                case Long value -> java.lang.Long.valueOf(value.value());
                case null, default -> null;
            };
            return Optional.ofNullable(number);
        }

        public int intOr(String name, int fallback) {
            return number(name).map(java.lang.Long::intValue).orElse(fallback);
        }

        public Optional<String> string(String name) {
            return get(name).filter(Str.class::isInstance).map(tag -> ((Str) tag).value());
        }

        /** The same compound with one name set. */
        public Compound with(String name, Tag value) {
            Map<String, Tag> copy = new LinkedHashMap<>(values);
            copy.put(name, value);
            return new Compound(copy);
        }

        /** The same compound with one name gone. */
        public Compound without(String name) {
            if (!values.containsKey(name)) {
                return this;
            }
            Map<String, Tag> copy = new LinkedHashMap<>(values);
            copy.remove(name);
            return new Compound(copy);
        }

        public int size() {
            return values.size();
        }
    }

    record IntArray(int[] value) implements Tag {
        public IntArray {
            value = value.clone();
        }

        @Override
        public int[] value() {
            return value.clone();
        }

        @Override
        public int id() {
            return INT_ARRAY;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IntArray that && Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "IntArray" + Arrays.toString(value);
        }
    }

    record LongArray(long[] value) implements Tag {
        public LongArray {
            value = value.clone();
        }

        @Override
        public long[] value() {
            return value.clone();
        }

        @Override
        public int id() {
            return LONG_ARRAY;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LongArray that && Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "LongArray" + Arrays.toString(value);
        }
    }

    /** A list built by hand — for a codec putting items back into a player file. */
    static List_ listOf(List<? extends Tag> items) {
        return List_.of(new ArrayList<>(items));
    }
}
