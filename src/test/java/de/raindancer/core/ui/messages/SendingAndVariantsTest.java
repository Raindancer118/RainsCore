package de.raindancer.core.ui.messages;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Saying something to somebody, and saying it differently each time.
 *
 * <p>{@code send} exists because {@code recipient.sendMessage(messages.prefixed(key, …))} was the same line in
 * hundreds of places, and the one that forgot the prefix was the one nobody noticed until a player asked which
 * plugin had just talked to them.
 *
 * <p>{@code variant} has one property worth pinning: a list of one must read exactly like a plain key. Get that
 * wrong and turning a message into a list of alternatives silently moves the prefix.
 */
class SendingAndVariantsTest {

    private static final String FILE = """
            prefix: "<gray>[<aqua>Core<gray>] "
            plain: "Nothing to report"
            greeting: "Hello <name>"
            refusals:
              - "You may not do that"
              - "No"
              - "Absolutely not"
            single:
              - "The only wording there is"
            empty-list: []
            """;

    private Messages messages;

    /** Records what it was told, so a test can read it back. */
    private static final class Ear implements Audience {
        final List<String> heard = new ArrayList<>();

        @Override
        public void sendMessage(Component message) {
            heard.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
    }

    private final Ear ear = new Ear();

    @BeforeEach
    void setUp() {
        messages = new Messages(Path.of("target", "no-such-messages.yml"));
        messages.load(new ByteArrayInputStream(FILE.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sendingPutsThePrefixInFront() {
        messages.send(ear, "plain");
        assertThat(ear.heard).singleElement().asString().isEqualTo("[Core] Nothing to report");
    }

    @Test
    void sendingPlainLeavesThePrefixOff() {
        messages.sendPlain(ear, "plain");
        assertThat(ear.heard).singleElement().asString().isEqualTo("Nothing to report");
    }

    @Test
    void sendingFillsInTheValues() {
        messages.send(ear, "greeting", "name", "Raindancer118");
        assertThat(ear.heard).singleElement().asString().contains("Hello Raindancer118");
    }

    @Test
    void sendingToNobodyIsNotACrash() {
        // Callers hand this the result of getPlayer(uuid), which is null the moment somebody logs out.
        assertThat(catchThrowable(() -> messages.send(null, "plain"))).isNull();
        assertThat(catchThrowable(() -> messages.sendPlain(null, "plain"))).isNull();
    }

    @Test
    void sendingAKeyNobodyDefinedStillSaysSomething() {
        // The key itself, which is ugly and findable. Silence would leave a player staring at nothing.
        messages.send(ear, "no.such.key");
        assertThat(ear.heard).singleElement().asString().contains("no.such.key");
    }

    @Test
    void aVariantIsOneOfTheOptions() {
        Component chosen = messages.variant("refusals");
        assertThat(PlainTextComponentSerializer.plainText().serialize(chosen))
                .isIn("[Core] You may not do that", "[Core] No", "[Core] Absolutely not");
    }

    @Test
    void aVariantEventuallyUsesMoreThanOneOption() {
        // Not a distribution test — just that it is not pinned to the first entry, which is what a broken
        // random choice looks like and what a reader would never notice.
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (int round = 0; round < 200; round++) {
            distinct.add(PlainTextComponentSerializer.plainText().serialize(messages.variant("refusals")));
        }
        assertThat(distinct).hasSizeGreaterThan(1);
    }

    @Test
    void aListOfOneReadsExactlyLikeAPlainKey() {
        // The property that matters: making a message varied must not move the prefix.
        assertThat(PlainTextComponentSerializer.plainText().serialize(messages.variant("single")))
                .isEqualTo("[Core] The only wording there is");
    }

    @Test
    void aKeyThatIsNotAListIsJustThatMessage() {
        assertThat(PlainTextComponentSerializer.plainText().serialize(messages.variant("plain")))
                .isEqualTo("[Core] Nothing to report");
    }

    @Test
    void anEmptyListFallsBackRatherThanThrowing() {
        // A file somebody edited down to nothing. Refusing to render is worse than rendering the key.
        assertThat(catchThrowable(() -> messages.variant("empty-list"))).isNull();
    }

    @Test
    void aVariantFillsInValuesToo() {
        assertThat(PlainTextComponentSerializer.plainText().serialize(
                messages.variant("greeting", "name", "Bentex_OG")))
                .contains("Hello Bentex_OG");
    }
}
