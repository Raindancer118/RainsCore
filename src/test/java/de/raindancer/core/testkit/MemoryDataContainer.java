package de.raindancer.core.testkit;

import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.ListPersistentDataType;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link PersistentDataContainer} that works without a server, holding the same primitive values the
 * server's does and refusing the same things: reading a value back as a type it cannot be is an
 * {@link IllegalArgumentException}, as on a server, not a silent null.
 *
 * <p>Values are copied going in and coming out, like the server's, so changing an array or a nested
 * container that was read does not change what is stored. {@link #serializeToBytes()} round-trips within
 * the testkit; the bytes are not NBT.
 */
public final class MemoryDataContainer implements PersistentDataContainer {

    private static final PersistentDataAdapterContext CONTEXT = MemoryDataContainer::new;

    private final Map<NamespacedKey, Object> values = new LinkedHashMap<>();

    @Override
    public <P, C> void set(NamespacedKey key, PersistentDataType<P, C> type, C value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        Object primitive = type.toPrimitive(value, CONTEXT);
        values.put(key, copy(checked(primitive)));
    }

    @Override
    public void remove(NamespacedKey key) {
        values.remove(key);
    }

    @Override
    public void readFromBytes(byte[] bytes, boolean clear) throws IOException {
        Map<NamespacedKey, Object> read = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                NamespacedKey key = NamespacedKey.fromString(in.readUTF());
                read.put(key, readValue(in));
            }
        }
        if (clear) {
            values.clear();
        }
        values.putAll(read);
    }

    @Override
    public <P, C> boolean has(NamespacedKey key, PersistentDataType<P, C> type) {
        Object stored = values.get(key);
        return stored != null && fits(stored, type);
    }

    @Override
    public boolean has(NamespacedKey key) {
        return values.containsKey(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <P, C> C get(NamespacedKey key, PersistentDataType<P, C> type) {
        Object stored = values.get(key);
        if (stored == null) {
            return null;
        }
        if (!fits(stored, type)) {
            throw new IllegalArgumentException("The value under " + key + " is " + describe(stored)
                    + ", which cannot be read as " + type.getComplexType().getSimpleName());
        }
        return type.fromPrimitive((P) copy(stored), CONTEXT);
    }

    @Override
    public <P, C> C getOrDefault(NamespacedKey key, PersistentDataType<P, C> type, C defaultValue) {
        C value = get(key, type);
        return value == null ? defaultValue : value;
    }

    @Override
    public Set<NamespacedKey> getKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values.keySet()));
    }

    @Override
    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public void copyTo(PersistentDataContainer other, boolean replace) {
        Objects.requireNonNull(other, "other");
        if (other instanceof MemoryDataContainer memory) {
            values.forEach((key, value) -> {
                if (replace || !memory.values.containsKey(key)) {
                    memory.values.put(key, copy(value));
                }
            });
            return;
        }
        throw new IllegalArgumentException("Can only copy into another testkit container, not " + other.getClass());
    }

    @Override
    public PersistentDataAdapterContext getAdapterContext() {
        return CONTEXT;
    }

    @Override
    public byte[] serializeToBytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(values.size());
            for (Map.Entry<NamespacedKey, Object> entry : values.entrySet()) {
                out.writeUTF(entry.getKey().toString());
                writeValue(out, entry.getValue());
            }
        }
        return bytes.toByteArray();
    }

    @Override
    public int getSize() {
        return values.size();
    }

    /** A copy of everything in here. */
    public MemoryDataContainer copy() {
        MemoryDataContainer copy = new MemoryDataContainer();
        copyTo(copy, true);
        return copy;
    }

    /** What an item hands out from {@code getPersistentDataContainer()}: reads this, cannot change it. */
    public PersistentDataContainerView view() {
        MemoryDataContainer source = this;
        return new PersistentDataContainerView() {
            @Override
            public <P, C> boolean has(NamespacedKey key, PersistentDataType<P, C> type) {
                return source.has(key, type);
            }

            @Override
            public boolean has(NamespacedKey key) {
                return source.has(key);
            }

            @Override
            public <P, C> C get(NamespacedKey key, PersistentDataType<P, C> type) {
                return source.get(key, type);
            }

            @Override
            public <P, C> C getOrDefault(NamespacedKey key, PersistentDataType<P, C> type, C defaultValue) {
                return source.getOrDefault(key, type, defaultValue);
            }

            @Override
            public Set<NamespacedKey> getKeys() {
                return source.getKeys();
            }

            @Override
            public boolean isEmpty() {
                return source.isEmpty();
            }

            @Override
            public void copyTo(PersistentDataContainer other, boolean replace) {
                source.copyTo(other, replace);
            }

            @Override
            public PersistentDataAdapterContext getAdapterContext() {
                return CONTEXT;
            }

            @Override
            public byte[] serializeToBytes() throws IOException {
                return source.serializeToBytes();
            }

            @Override
            public int getSize() {
                return source.getSize();
            }

            @Override
            public String toString() {
                return source.toString();
            }
        };
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MemoryDataContainer that) || values.size() != that.values.size()) {
            return false;
        }
        for (Map.Entry<NamespacedKey, Object> entry : values.entrySet()) {
            if (!Objects.deepEquals(normal(entry.getValue()), normal(that.values.get(entry.getKey())))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 0;
        for (Map.Entry<NamespacedKey, Object> entry : values.entrySet()) {
            hash += entry.getKey().hashCode() ^ Arrays.deepHashCode(new Object[]{normal(entry.getValue())});
        }
        return hash;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("{");
        values.forEach((key, value) -> {
            if (text.length() > 1) {
                text.append(", ");
            }
            String shown = Arrays.deepToString(new Object[]{normal(value)});
            text.append(key).append('=').append(shown, 1, shown.length() - 1);
        });
        return text.append('}').toString();
    }

    // Arrays of containers compare by content; everything else already does.
    private static Object normal(Object value) {
        if (value instanceof PersistentDataContainer[] containers) {
            return Arrays.asList(containers);
        }
        return value;
    }

    private static boolean fits(Object stored, PersistentDataType<?, ?> type) {
        Class<?> primitive = type.getPrimitiveType();
        if (!primitive.isInstance(stored)) {
            return false;
        }
        if (type instanceof ListPersistentDataType<?, ?> list && stored instanceof List<?> elements) {
            for (Object element : elements) {
                if (!fits(element, list.elementType())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Object checked(Object primitive) {
        if (primitive instanceof Byte || primitive instanceof Short || primitive instanceof Integer
                || primitive instanceof Long || primitive instanceof Float || primitive instanceof Double
                || primitive instanceof String || primitive instanceof byte[] || primitive instanceof int[]
                || primitive instanceof long[] || primitive instanceof MemoryDataContainer) {
            return primitive;
        }
        if (primitive instanceof PersistentDataContainer[] containers) {
            for (PersistentDataContainer container : containers) {
                checked(container);
            }
            return primitive;
        }
        if (primitive instanceof List<?> list) {
            list.forEach(MemoryDataContainer::checked);
            return primitive;
        }
        throw new IllegalArgumentException(primitive == null
                ? "A data type turned a value into null"
                : primitive instanceof PersistentDataContainer
                ? "A container not made by this one's adapter context: " + primitive.getClass()
                : primitive.getClass().getName() + " is not a type a data container can hold");
    }

    private static Object copy(Object value) {
        return switch (value) {
            case byte[] bytes -> bytes.clone();
            case int[] ints -> ints.clone();
            case long[] longs -> longs.clone();
            case MemoryDataContainer container -> container.copy();
            case PersistentDataContainer[] containers -> {
                PersistentDataContainer[] copied = new PersistentDataContainer[containers.length];
                for (int i = 0; i < containers.length; i++) {
                    copied[i] = (PersistentDataContainer) copy(containers[i]);
                }
                yield copied;
            }
            case List<?> list -> {
                List<Object> copied = new ArrayList<>(list.size());
                list.forEach(element -> copied.add(copy(element)));
                yield Collections.unmodifiableList(copied);
            }
            default -> value;
        };
    }

    private static String describe(Object stored) {
        return stored instanceof List<?> ? "a list" : "a " + stored.getClass().getSimpleName();
    }

    private static void writeValue(DataOutputStream out, Object value) throws IOException {
        switch (value) {
            case Byte b -> { out.writeByte(1); out.writeByte(b); }
            case Short s -> { out.writeByte(2); out.writeShort(s); }
            case Integer i -> { out.writeByte(3); out.writeInt(i); }
            case Long l -> { out.writeByte(4); out.writeLong(l); }
            case Float f -> { out.writeByte(5); out.writeFloat(f); }
            case Double d -> { out.writeByte(6); out.writeDouble(d); }
            case String s -> { out.writeByte(7); out.writeUTF(s); }
            case byte[] bytes -> { out.writeByte(8); out.writeInt(bytes.length); out.write(bytes); }
            case int[] ints -> {
                out.writeByte(9);
                out.writeInt(ints.length);
                for (int i : ints) {
                    out.writeInt(i);
                }
            }
            case long[] longs -> {
                out.writeByte(10);
                out.writeInt(longs.length);
                for (long l : longs) {
                    out.writeLong(l);
                }
            }
            case MemoryDataContainer container -> {
                out.writeByte(11);
                byte[] nested = container.serializeToBytes();
                out.writeInt(nested.length);
                out.write(nested);
            }
            case PersistentDataContainer[] containers -> {
                out.writeByte(12);
                out.writeInt(containers.length);
                for (PersistentDataContainer container : containers) {
                    writeValue(out, container);
                }
            }
            case List<?> list -> {
                out.writeByte(13);
                out.writeInt(list.size());
                for (Object element : list) {
                    writeValue(out, element);
                }
            }
            default -> throw new IOException("Cannot write " + value.getClass());
        }
    }

    private static Object readValue(DataInputStream in) throws IOException {
        int tag = in.readByte();
        return switch (tag) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> in.readUTF();
            case 8 -> in.readNBytes(in.readInt());
            case 9 -> {
                int[] ints = new int[in.readInt()];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = in.readInt();
                }
                yield ints;
            }
            case 10 -> {
                long[] longs = new long[in.readInt()];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = in.readLong();
                }
                yield longs;
            }
            case 11 -> {
                MemoryDataContainer nested = new MemoryDataContainer();
                nested.readFromBytes(in.readNBytes(in.readInt()), true);
                yield nested;
            }
            case 12 -> {
                PersistentDataContainer[] containers = new PersistentDataContainer[in.readInt()];
                for (int i = 0; i < containers.length; i++) {
                    containers[i] = (PersistentDataContainer) readValue(in);
                }
                yield containers;
            }
            case 13 -> {
                int size = in.readInt();
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(readValue(in));
                }
                yield Collections.unmodifiableList(list);
            }
            default -> throw new IOException("Not testkit container bytes (tag " + tag + ")");
        };
    }
}
