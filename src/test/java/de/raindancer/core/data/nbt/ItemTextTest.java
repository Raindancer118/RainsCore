package de.raindancer.core.data.nbt;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Items as text, without a server.
 *
 * <p>The serialisation itself is the server's and is not tested here — {@link ItemBytes} is the seam
 * precisely so that everything around it can be. What is tested is the part that goes wrong on somebody's
 * machine: a file a human edited, a truncated line, an item from a plugin that has since been removed.
 * Each of those has to come back as nothing rather than as an exception, because the caller is a loader
 * and a loader that throws leaves land unprotected.
 */
class ItemTextTest {

    /** Stands in for the server: the "bytes" of an item are its name, so a test can read them. */
    private static final class Fake implements ItemBytes {

        final List<byte[]> refuses = new ArrayList<>();
        RuntimeException throwOnRead;

        @Override
        public int dataVersion() {
            return 4189;
        }

        @Override
        public byte[] toBytes(ItemStack item) {
            return new byte[]{1, 2, 3};
        }

        /**
         * Overridden because the real one asks {@code Material.isAir()}, which resolves through Paper's
         * registry and throws without a server. That is the whole reason the question is on this interface.
         */
        @Override
        public boolean isNothing(ItemStack item) {
            return item == null;
        }

        @Override
        public Optional<ItemStack> fromBytes(byte[] bytes) {
            if (throwOnRead != null) {
                throw throwOnRead;
            }
            for (byte[] refused : refuses) {
                if (Arrays.equals(refused, bytes)) {
                    return Optional.empty();
                }
            }
            // Cannot make a real ItemStack without a server, and the identity of the item is not what
            // this class is responsible for. What matters is that the bytes arrived unaltered.
            assertThat(bytes).containsExactly(1, 2, 3);
            return Optional.empty();
        }
    }

    private final Fake server = new Fake();
    private final ItemText codec = new ItemText(server);

    @Test
    void nothingEncodesToNothing() {
        assertThat(codec.write(null)).isNull();
    }

    @Test
    void nothingDecodesToNothing() {
        assertThat(codec.read(null)).isNull();
        assertThat(codec.read("")).isNull();
        assertThat(codec.read("   ")).isNull();
    }

    @Test
    void textThatIsNotBase64ComesBackAsNothingRatherThanThrowing() {
        assertThat(catchThrowable(() -> codec.read("this is not base64 !!!"))).isNull();
        assertThat(codec.read("this is not base64 !!!")).isNull();
    }

    @Test
    void aTruncatedLineComesBackAsNothing() {
        // Half a line is what a file cut short by a full disk looks like.
        String full = codec.write(new FakeStack());
        assertThat(codec.read(full.substring(0, full.length() / 2) + "=")).isNull();
    }

    @Test
    void anItemTheServerRefusesComesBackAsNothing() {
        server.refuses.add(new byte[]{1, 2, 3});
        assertThat(codec.read(codec.write(new FakeStack()))).isNull();
    }

    @Test
    void aSerialiserThatThrowsIsStillJustNothing() {
        // The one that matters: a block from a plugin that is no longer installed. The rest of the
        // chest is still worth loading.
        server.throwOnRead = new IllegalStateException("no such material any more");
        assertThat(catchThrowable(() -> codec.read(codec.write(new FakeStack())))).isNull();
    }

    @Test
    void encodingIsWhitespaceTolerantOnTheWayBackIn() {
        // A YAML reader can hand back a line with a trailing newline or a stray space.
        String encoded = codec.write(new FakeStack());
        assertThat(catchThrowable(() -> codec.read("  " + encoded + "\n"))).isNull();
    }

    @Test
    void aListSkipsWhatCouldNotBeWrittenRatherThanHoldingNulls() {
        List<ItemStack> items = new ArrayList<>();
        items.add(new FakeStack());
        items.add(null);
        items.add(new FakeStack());

        assertThat(codec.writeAll(items)).hasSize(2);
    }

    @Test
    void aListSkipsWhatCouldNotBeReadRatherThanHoldingNulls() {
        List<String> encoded = List.of("not base64 !!", codec.write(new FakeStack()));
        server.refuses.add(new byte[]{1, 2, 3});

        assertThat(codec.readAll(encoded)).isEmpty();
    }

    @Test
    void aMissingListIsAnEmptyOneRatherThanAFailure() {
        assertThat(codec.writeAll(null)).isEmpty();
        assertThat(codec.readAll(null)).isEmpty();
    }

    @Test
    void needsSomethingToSerialiseWith() {
        assertThat(catchThrowable(() -> new ItemText(null))).isInstanceOf(NullPointerException.class);
    }

    /**
     * A stand-in item. Nothing is ever asked of it — {@link ItemBytes#isNothing} and
     * {@link ItemBytes#toBytes} are both answered by the fake serialiser, which is the point of having the
     * seam at all: no part of this test needs a server.
     */
    private static final class FakeStack extends ItemStack {
    }
}
