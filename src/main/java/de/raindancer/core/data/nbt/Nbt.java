package de.raindancer.core.data.nbt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Reading and writing Minecraft's save format.
 *
 * <h2>The shape of a file</h2>
 * A gzipped stream holding one tag: a type byte, a name, and the value. The root is always a
 * compound and its name is always empty, but both are written anyway because that is what the game
 * expects to read back.
 *
 * <p>Strings are Java's own {@code writeUTF} — length-prefixed modified UTF-8 — which is not a
 * coincidence: NBT was written in Java against exactly these two methods.
 *
 * <h2>Two limits, both deliberate</h2>
 * A malformed or hostile file must not be able to make this allocate the heap. Lists and arrays are
 * checked against what is left of the stream before anything is reserved, and nesting is capped, so
 * a broken player file is an exception with a filename in it rather than an out-of-memory kill.
 */
public final class Nbt {

    /** Deeper than any real save, shallow enough that a cycle in a corrupt file cannot recurse away
     * the stack. */
    private static final int MAX_DEPTH = 512;

    /**
     * The most bytes any one array in a file may claim.
     *
     * <p>Counted in <b>bytes</b>, not elements, which is the correction: a cap of sixteen million
     * *elements* is sixteen megabytes for a byte array and a hundred and thirty-four for an array of
     * longs — and the allocation happens before a single byte of it has been read, so a corrupt
     * four-byte length is enough to take a chunk of the heap. A handful of those in one file is an
     * OutOfMemoryError, which is precisely what this constant is here to prevent.
     */
    private static final int MAX_ARRAY_BYTES = 16 << 20;

    private Nbt() {
    }

    // ----------------------------------------------------------------------------- reading

    /** The root compound of a gzipped NBT file. */
    public static Tag.Compound read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return readCompressed(in);
        }
    }

    public static Tag.Compound readCompressed(byte[] bytes) throws IOException {
        return readCompressed(new ByteArrayInputStream(bytes));
    }

    public static Tag.Compound readCompressed(InputStream in) throws IOException {
        try (DataInputStream data = new DataInputStream(new GZIPInputStream(in))) {
            return readRoot(data);
        }
    }

    /** For a stream that is already plain — the same layout without the gzip. */
    public static Tag.Compound readPlain(byte[] bytes) throws IOException {
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return readRoot(data);
        }
    }

    private static Tag.Compound readRoot(DataInput data) throws IOException {
        int type = data.readUnsignedByte();
        if (type != Tag.COMPOUND) {
            throw new IOException("This is not an NBT file: it starts with tag type " + type
                    + " rather than a compound.");
        }
        // The root's name. Always empty in practice, always present in the format.
        data.readUTF();
        return (Tag.Compound) readValue(data, Tag.COMPOUND, 0);
    }

    private static Tag readValue(DataInput data, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nested more than " + MAX_DEPTH + " deep, which no real save "
                    + "is: the file is corrupt.");
        }
        return switch (type) {
            case Tag.BYTE -> new Tag.Byte(data.readByte());
            case Tag.SHORT -> new Tag.Short(data.readShort());
            case Tag.INT -> new Tag.Int(data.readInt());
            case Tag.LONG -> new Tag.Long(data.readLong());
            case Tag.FLOAT -> new Tag.Float(data.readFloat());
            case Tag.DOUBLE -> new Tag.Double(data.readDouble());
            case Tag.BYTE_ARRAY -> new Tag.ByteArray(readBytes(data));
            case Tag.STRING -> new Tag.Str(data.readUTF());
            case Tag.LIST -> readList(data, depth);
            case Tag.COMPOUND -> readCompound(data, depth);
            case Tag.INT_ARRAY -> readIntArray(data);
            case Tag.LONG_ARRAY -> readLongArray(data);
            default -> throw new IOException("Unknown NBT tag type " + type + ".");
        };
    }

    private static Tag readList(DataInput data, int depth) throws IOException {
        int elementType = data.readUnsignedByte();
        int count = count(data.readInt());
        if (elementType == Tag.END && count > 0) {
            throw new IOException("A list of nothing cannot have " + count + " entries.");
        }
        List<Tag> items = new ArrayList<>(Math.min(count, 1024));
        for (int at = 0; at < count; at++) {
            items.add(readValue(data, elementType, depth + 1));
        }
        return new Tag.List_(elementType, items);
    }

    private static Tag readCompound(DataInput data, int depth) throws IOException {
        Map<String, Tag> values = new LinkedHashMap<>();
        while (true) {
            int type = data.readUnsignedByte();
            if (type == Tag.END) {
                return new Tag.Compound(values);
            }
            String name = data.readUTF();
            values.put(name, readValue(data, type, depth + 1));
        }
    }

    private static byte[] readBytes(DataInput data) throws IOException {
        byte[] bytes = new byte[length(data.readInt(), Byte.BYTES)];
        data.readFully(bytes);
        return bytes;
    }

    private static Tag readIntArray(DataInput data) throws IOException {
        int[] values = new int[length(data.readInt(), Integer.BYTES)];
        for (int at = 0; at < values.length; at++) {
            values[at] = data.readInt();
        }
        return new Tag.IntArray(values);
    }

    private static Tag readLongArray(DataInput data) throws IOException {
        long[] values = new long[length(data.readInt(), Long.BYTES)];
        for (int at = 0; at < values.length; at++) {
            values[at] = data.readLong();
        }
        return new Tag.LongArray(values);
    }

    /**
     * Checks a claimed length before anything is allocated for it.
     *
     * @param bytesEach how wide one element is, so the cap is a size rather than a count
     */
    private static int length(int claimed, int bytesEach) throws IOException {
        if (claimed < 0 || (long) claimed * bytesEach > MAX_ARRAY_BYTES) {
            throw new IOException("An NBT length of " + claimed + " is not a real one.");
        }
        return claimed;
    }

    /**
     * A count of tags rather than of fixed-width values.
     *
     * <p>Capped by count, because a tag's size is not known until it is read. Each one still costs at
     * least a byte to read, so a list claiming more entries than the byte cap cannot be honest either.
     */
    private static int count(int claimed) throws IOException {
        if (claimed < 0 || claimed > MAX_ARRAY_BYTES) {
            throw new IOException("An NBT length of " + claimed + " is not a real one.");
        }
        return claimed;
    }

    // ----------------------------------------------------------------------------- writing

    public static byte[] writeCompressed(Tag.Compound root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(bytes))) {
            writeRoot(data, root);
        }
        return bytes.toByteArray();
    }

    public static byte[] writePlain(Tag.Compound root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            writeRoot(data, root);
        }
        return bytes.toByteArray();
    }

    /**
     * Writes a file the way this project writes every file: beside it, then moved over it.
     *
     * <p>A player file half-written because the machine lost power mid-save is a player who has lost
     * everything, and the move is atomic on every filesystem these run on.
     */
    public static void write(Path file, Tag.Compound root) throws IOException {
        byte[] bytes = writeCompressed(root);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".writing");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeRoot(DataOutput data, Tag.Compound root) throws IOException {
        data.writeByte(Tag.COMPOUND);
        data.writeUTF("");
        writeValue(data, root);
    }

    private static void writeValue(DataOutput data, Tag tag) throws IOException {
        switch (tag) {
            case Tag.Byte value -> data.writeByte(value.value());
            case Tag.Short value -> data.writeShort(value.value());
            case Tag.Int value -> data.writeInt(value.value());
            case Tag.Long value -> data.writeLong(value.value());
            case Tag.Float value -> data.writeFloat(value.value());
            case Tag.Double value -> data.writeDouble(value.value());
            case Tag.ByteArray value -> {
                byte[] bytes = value.value();
                data.writeInt(bytes.length);
                data.write(bytes);
            }
            case Tag.Str value -> data.writeUTF(value.value());
            case Tag.List_ value -> {
                data.writeByte(value.elementType());
                data.writeInt(value.size());
                for (Tag item : value.items()) {
                    writeValue(data, item);
                }
            }
            case Tag.Compound value -> {
                for (Map.Entry<String, Tag> entry : value.values().entrySet()) {
                    data.writeByte(entry.getValue().id());
                    data.writeUTF(entry.getKey());
                    writeValue(data, entry.getValue());
                }
                data.writeByte(Tag.END);
            }
            case Tag.IntArray value -> {
                int[] values = value.value();
                data.writeInt(values.length);
                for (int number : values) {
                    data.writeInt(number);
                }
            }
            case Tag.LongArray value -> {
                long[] values = value.value();
                data.writeInt(values.length);
                for (long number : values) {
                    data.writeLong(number);
                }
            }
        }
    }

    /** For a caller that has its own stream — the plugin's pack writer does. */
    public static void writeCompressed(OutputStream out, Tag.Compound root) throws IOException {
        try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(out))) {
            writeRoot(data, root);
        }
    }
}
