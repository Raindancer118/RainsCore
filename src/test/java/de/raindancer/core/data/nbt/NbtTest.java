package de.raindancer.core.data.nbt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading and writing the format the server saves players in.
 *
 * <h2>What these tests are really protecting</h2>
 * A player file. Every one of these round-trips is somebody's inventory, and the failure mode of a
 * writer that is subtly wrong is not an exception — it is a player who logs in tomorrow having lost
 * everything. So the tests are round-trips through real bytes rather than assertions about fields,
 * and the awkward cases are here on purpose: empty lists, nested compounds, unsigned bytes, strings
 * with characters outside Latin-1, and files that are simply not NBT.
 */
@DisplayName("Minecraft's save format")
class NbtTest {

    private static Tag.Compound roundTrip(Tag.Compound root) throws IOException {
        return Nbt.readCompressed(Nbt.writeCompressed(root));
    }

    private static Tag.Compound compound(Map<String, Tag> values) {
        return new Tag.Compound(new LinkedHashMap<>(values));
    }

    @Nested
    @DisplayName("every kind of value survives a round trip")
    class Values {

        @Test
        @DisplayName("the numbers, at their limits")
        void numbers() throws IOException {
            Tag.Compound root = compound(Map.of(
                    "byte", new Tag.Byte((byte) -128),
                    "short", new Tag.Short(Short.MAX_VALUE),
                    "int", new Tag.Int(Integer.MIN_VALUE),
                    "long", new Tag.Long(Long.MAX_VALUE),
                    "float", new Tag.Float(-0.5f),
                    "double", new Tag.Double(Math.PI)));

            assertThat(roundTrip(root)).isEqualTo(root);
        }

        @Test
        @DisplayName("a byte above 127, which is where a sign error would show")
        void unsignedLookingByte() throws IOException {
            // Read back through readUnsignedByte for the type and readByte for the value: getting
            // one of those the wrong way round is the classic NBT bug.
            Tag.Compound root = compound(Map.of("count", new Tag.Byte((byte) 200)));
            assertThat(roundTrip(root)).isEqualTo(root);
            assertThat(roundTrip(root).number("count")).contains(-56L);
        }

        @Test
        @DisplayName("strings, including ones outside Latin-1")
        void strings() throws IOException {
            Tag.Compound root = compound(Map.of(
                    "plain", new Tag.Str("Steve"),
                    "german", new Tag.Str("Grüße, Höhle"),
                    "emoji", new Tag.Str("a pickaxe ⛏"),
                    "empty", new Tag.Str("")));

            assertThat(roundTrip(root)).isEqualTo(root);
        }

        @Test
        @DisplayName("the three array kinds, empty and full")
        void arrays() throws IOException {
            Tag.Compound root = compound(Map.of(
                    "bytes", new Tag.ByteArray(new byte[] {1, -1, 0, 127}),
                    "noBytes", new Tag.ByteArray(new byte[0]),
                    "ints", new Tag.IntArray(new int[] {Integer.MAX_VALUE, 0, -7}),
                    "uuid", new Tag.IntArray(new int[] {1, 2, 3, 4}),
                    "longs", new Tag.LongArray(new long[] {Long.MIN_VALUE, 12L})));

            assertThat(roundTrip(root)).isEqualTo(root);
        }

        @Test
        @DisplayName("a list of compounds — which is what an inventory is")
        void listOfCompounds() throws IOException {
            Tag.List_ items = Tag.List_.of(List.of(
                    compound(Map.of("id", new Tag.Str("minecraft:stone"),
                            "count", new Tag.Int(64), "Slot", new Tag.Byte((byte) 0))),
                    compound(Map.of("id", new Tag.Str("minecraft:torch"),
                            "count", new Tag.Int(1), "Slot", new Tag.Byte((byte) 100)))));
            Tag.Compound root = compound(Map.of("Inventory", items));

            Tag.Compound back = roundTrip(root);
            assertThat(back).isEqualTo(root);
            assertThat(back.list("Inventory").orElseThrow().size()).isEqualTo(2);
        }

        @Test
        @DisplayName("an empty list, which has no element type to write")
        void emptyList() throws IOException {
            Tag.Compound root = compound(Map.of("Inventory", Tag.List_.empty()));

            Tag.Compound back = roundTrip(root);
            assertThat(back).isEqualTo(root);
            assertThat(back.list("Inventory").orElseThrow().elementType()).isEqualTo(Tag.END);
        }

        @Test
        @DisplayName("compounds inside compounds inside lists")
        void deeplyNested() throws IOException {
            Tag.Compound inner = compound(Map.of("damage", new Tag.Int(3)));
            Tag.Compound components = compound(Map.of("minecraft:damage", inner));
            Tag.Compound item = compound(Map.of(
                    "id", new Tag.Str("minecraft:diamond_pickaxe"),
                    "components", components));
            Tag.Compound root = compound(Map.of("Inventory", Tag.List_.of(List.of(item))));

            assertThat(roundTrip(root)).isEqualTo(root);
        }

        @Test
        @DisplayName("an empty compound")
        void emptyCompound() throws IOException {
            assertThat(roundTrip(Tag.Compound.empty())).isEqualTo(Tag.Compound.empty());
        }

        @Test
        @DisplayName("the order names were written in is kept")
        void keepsOrder() throws IOException {
            Map<String, Tag> values = new LinkedHashMap<>();
            values.put("zebra", new Tag.Int(1));
            values.put("apple", new Tag.Int(2));
            values.put("moose", new Tag.Int(3));

            assertThat(roundTrip(new Tag.Compound(values)).values().keySet())
                    .as("a file rewritten with its names shuffled is a needless diff, and makes "
                            + "comparing two saves useless")
                    .containsExactly("zebra", "apple", "moose");
        }
    }

    @Nested
    @DisplayName("values are immutable")
    class Immutability {

        @Test
        @DisplayName("an array handed in cannot be changed afterwards")
        void copiesOnTheWayIn() {
            byte[] mine = {1, 2, 3};
            Tag.ByteArray tag = new Tag.ByteArray(mine);
            mine[0] = 99;

            assertThat(tag.value()).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("an array handed out cannot be changed either")
        void copiesOnTheWayOut() {
            Tag.IntArray tag = new Tag.IntArray(new int[] {1, 2, 3});
            tag.value()[0] = 99;

            assertThat(tag.value()).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("arrays compare by what is in them, not by which array they are")
        void arraysCompareByContent() {
            assertThat(new Tag.ByteArray(new byte[] {1, 2}))
                    .isEqualTo(new Tag.ByteArray(new byte[] {1, 2}))
                    .hasSameHashCodeAs(new Tag.ByteArray(new byte[] {1, 2}));
            assertThat(new Tag.LongArray(new long[] {1}))
                    .isNotEqualTo(new Tag.LongArray(new long[] {2}));
        }

        @Test
        @DisplayName("adding a name gives a new compound rather than changing this one")
        void withIsCopyOnWrite() {
            Tag.Compound before = compound(Map.of("a", new Tag.Int(1)));
            Tag.Compound after = before.with("b", new Tag.Int(2));

            assertThat(before.has("b")).isFalse();
            assertThat(after.has("b")).isTrue();
            assertThat(after.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("removing a name that is not there changes nothing at all")
        void withoutIsCheapWhenAbsent() {
            Tag.Compound before = compound(Map.of("a", new Tag.Int(1)));

            assertThat(before.without("nope")).isSameAs(before);
            assertThat(before.without("a").has("a")).isFalse();
        }
    }

    @Nested
    @DisplayName("asking a compound for things")
    class Asking {

        private final Tag.Compound root = compound(Map.of(
                "DataVersion", new Tag.Int(4189),
                "Slot", new Tag.Byte((byte) 3),
                "big", new Tag.Long(70000L),
                "name", new Tag.Str("Steve"),
                "Inventory", Tag.List_.empty(),
                "inner", Tag.Compound.empty()));

        @Test
        @DisplayName("a number is a number whatever width it was stored as")
        void numbersOfAnyWidth() {
            assertThat(root.number("DataVersion")).contains(4189L);
            assertThat(root.number("Slot")).contains(3L);
            assertThat(root.number("big")).contains(70000L);
        }

        @Test
        @DisplayName("asking for something that is not there, or is the wrong kind, is empty")
        void wrongKindIsEmpty() {
            assertThat(root.number("name")).isEmpty();
            assertThat(root.number("missing")).isEmpty();
            assertThat(root.compound("name")).isEmpty();
            assertThat(root.list("name")).isEmpty();
            assertThat(root.string("Slot")).isEmpty();
            assertThat(root.compound("inner")).isPresent();
            assertThat(root.list("Inventory")).isPresent();
        }

        @Test
        @DisplayName("a fallback is used when the number is missing rather than a zero pretending")
        void fallback() {
            assertThat(root.intOr("DataVersion", -1)).isEqualTo(4189);
            assertThat(root.intOr("missing", -1)).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("files")
    class Files_ {

        @TempDir
        Path folder;

        @Test
        @DisplayName("written and read back")
        void writtenAndRead() throws IOException {
            Path file = folder.resolve("world/playerdata/somebody.dat");
            Tag.Compound root = compound(Map.of("Health", new Tag.Float(20f)));

            Nbt.write(file, root);

            assertThat(Nbt.read(file)).isEqualTo(root);
        }

        @Test
        @DisplayName("nothing is left behind beside it")
        void leavesNoTemporaryFile() throws IOException {
            Path file = folder.resolve("player.dat");
            Nbt.write(file, compound(Map.of("a", new Tag.Int(1))));

            assertThat(Files.list(folder).map(Path::getFileName).map(Path::toString))
                    .as("a leftover .writing file is a file somebody has to explain later")
                    .containsExactly("player.dat");
        }

        @Test
        @DisplayName("an existing file is replaced whole, never half")
        void replacesInOnePiece() throws IOException {
            Path file = folder.resolve("player.dat");
            Nbt.write(file, compound(Map.of("first", new Tag.Int(1))));
            Nbt.write(file, compound(Map.of("second", new Tag.Int(2))));

            Tag.Compound back = Nbt.read(file);
            assertThat(back.has("first")).isFalse();
            assertThat(back.has("second")).isTrue();
        }

        @Test
        @DisplayName("what the game writes is what this reads: gzip, typed root, empty root name")
        void readsTheGamesOwnLayout() throws IOException {
            // Built by hand rather than by the writer under test, so this cannot agree with itself
            // about a format that is actually wrong.
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(raw)) {
                gzip.write(Tag.COMPOUND);
                gzip.write(new byte[] {0, 0});       // the root's name: two length bytes, no text
                gzip.write(Tag.INT);
                gzip.write(new byte[] {0, 11});      // "DataVersion" is eleven characters
                gzip.write("DataVersion".getBytes(StandardCharsets.UTF_8));
                gzip.write(new byte[] {0, 0, 16, 93});
                gzip.write(Tag.END);
            }

            assertThat(Nbt.readCompressed(raw.toByteArray()).intOr("DataVersion", -1))
                    .isEqualTo(4189);
        }
    }

    @Nested
    @DisplayName("files that are not what they claim")
    class Broken {

        @Test
        @DisplayName("something that is not gzipped at all")
        void notGzipped() {
            assertThatThrownBy(() -> Nbt.readCompressed("hello".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("gzipped, but not starting with a compound")
        void notACompound() throws IOException {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(raw)) {
                gzip.write(Tag.INT);
                gzip.write(new byte[] {0, 0, 0, 0, 0, 1});
            }

            assertThatThrownBy(() -> Nbt.readCompressed(raw.toByteArray()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("not an NBT file");
        }

        @Test
        @DisplayName("a tag type the format does not have")
        void unknownTagType() throws IOException {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(raw)) {
                gzip.write(Tag.COMPOUND);
                gzip.write(new byte[] {0, 0});
                gzip.write(99);
                gzip.write(new byte[] {0, 1});
                gzip.write("x".getBytes(StandardCharsets.UTF_8));
            }

            assertThatThrownBy(() -> Nbt.readCompressed(raw.toByteArray()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName("a length that would allocate the heap is refused, not attempted")
        void absurdLength() throws IOException {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(raw)) {
                gzip.write(Tag.COMPOUND);
                gzip.write(new byte[] {0, 0});
                gzip.write(Tag.BYTE_ARRAY);
                gzip.write(new byte[] {0, 1});
                gzip.write("x".getBytes(StandardCharsets.UTF_8));
                // Two billion bytes, claimed by four bytes of a corrupt file.
                gzip.write(new byte[] {0x7f, -1, -1, -1});
            }

            assertThatThrownBy(() -> Nbt.readCompressed(raw.toByteArray()))
                    .as("a corrupt file must be an exception with a reason, not an "
                            + "OutOfMemoryError that takes the server with it")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("not a real one");
        }

        @Test
        @DisplayName("a file that stops in the middle")
        void truncated() throws IOException {
            byte[] whole = Nbt.writeCompressed(compound(Map.of(
                    "Inventory", Tag.List_.of(List.of(compound(Map.of("id",
                            new Tag.Str("minecraft:stone"))))))));
            byte[] half = new byte[whole.length / 2];
            System.arraycopy(whole, 0, half, 0, half.length);

            assertThatThrownBy(() -> Nbt.readCompressed(half)).isInstanceOf(IOException.class);
        }
    }
}
