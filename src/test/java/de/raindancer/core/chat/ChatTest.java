package de.raindancer.core.chat;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What a plugin says in chat.
 *
 * <p>The two things worth pinning down are the tag — every message carries it, so a server that
 * changes it changes all of them — and the escaping, which is the one place a bug here becomes a
 * security problem rather than a cosmetic one.
 */
class ChatTest {

    private Recorder alice;
    private Recorder bob;
    private Recorder console;
    private Chat chat;

    @BeforeEach
    void setUp() {
        alice = new Recorder();
        bob = new Recorder();
        console = new Recorder();
        chat = new Chat(new Brand("RSC"), new FakeServer(List.of(alice, bob), console));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // ------------------------------------------------------------------ the tag

    @Nested
    @DisplayName("the plugin's tag")
    class Tag {

        @Test
        @DisplayName("is in front of anything addressed to somebody")
        void prefixesMessages() {
            chat.tell(alice, "Home set");
            assertThat(plain(alice.only())).isEqualTo("RSC » Home set");
        }

        @Test
        @DisplayName("is not in front of a row of a list")
        void doesNotPrefixRows() {
            chat.row(alice, "  base — 120, 64, -310");
            assertThat(plain(alice.only()))
                    .as("a twelve-row list with a tag on every row is twelve tags and one list")
                    .isEqualTo("  base — 120, 64, -310");
        }

        @Test
        @DisplayName("follows the brand, so changing it changes every message at once")
        void followsTheBrand() {
            Brand brand = new Brand("RSC");
            Chat renamed = new Chat(brand, new FakeServer(List.of(alice), console));
            brand.configure(() -> "YeukSMP", () -> true);

            renamed.tell(alice, "Home set");
            assertThat(plain(alice.only())).startsWith("YeukSMP »");
        }

        @Test
        @DisplayName("disappears entirely when the server switches it off")
        void canBeSwitchedOff() {
            Brand brand = new Brand("RSC").configure(null, () -> false);
            new Chat(brand, new FakeServer(List.of(alice), console)).tell(alice, "Home set");
            assertThat(plain(alice.only())).isEqualTo("Home set");
        }
    }

    // ------------------------------------------------------------------ escaping

    @Nested
    @DisplayName("text a player typed")
    class Escaping {

        @Test
        @DisplayName("cannot recolour the message it is pasted into")
        void argIsNotParsed() {
            chat.tell(alice, "Home <name> set", Chat.arg("name", "<red>"));
            assertThat(plain(alice.only()))
                    .as("a home called <red> is five characters, not a colour")
                    .isEqualTo("RSC » Home <red> set");
        }

        @Test
        @DisplayName("cannot swallow the rest of the line with an unclosed tag")
        void anUnclosedTagIsHarmless() {
            chat.tell(alice, "Claim <name> is yours", Chat.arg("name", "<gradient:red:blue>"));
            assertThat(plain(alice.only())).endsWith("is yours");
        }

        @Test
        @DisplayName("survives being null or empty rather than printing 'null'")
        void handlesNothing() {
            chat.tell(alice, "Home <name> set", Chat.arg("name", null));
            assertThat(plain(alice.only())).isEqualTo("RSC » Home  set");
        }

        @Test
        @DisplayName("formatted() is parsed on purpose, for text the server built")
        void formattedIsParsed() {
            chat.tell(alice, "<heading>",
                    Chat.formatted("heading", Component.text("Your claims")));
            assertThat(plain(alice.only())).isEqualTo("RSC » Your claims");
        }
    }

    // ------------------------------------------------------------------ audiences

    @Nested
    @DisplayName("who hears it")
    class Recipients {

        @Test
        @DisplayName("a broadcast reaches everybody, once each")
        void broadcastsToEverybody() {
            chat.broadcast("The market is open");
            assertThat(plain(alice.only())).isEqualTo("RSC » The market is open");
            assertThat(plain(bob.only())).isEqualTo("RSC » The market is open");
            assertThat(console.received).isEmpty();
        }

        @Test
        @DisplayName("a broadcast to a chosen few reaches only them")
        void broadcastsToSome() {
            chat.broadcast(List.of(bob), "The council has been asked");
            assertThat(alice.received).isEmpty();
            assertThat(bob.received).hasSize(1);
        }

        @Test
        @DisplayName("the console is a recipient like any other, and gets the tag")
        void talksToTheConsole() {
            chat.console("Loaded 41 claims");
            assertThat(plain(console.only())).isEqualTo("RSC » Loaded 41 claims");
            assertThat(alice.received).isEmpty();
        }

        @Test
        @DisplayName("a null recipient is ignored rather than throwing inside a listener")
        void survivesANullRecipient() {
            assertThatCode(() -> {
                chat.tell(null, "nobody");
                chat.row(null, "nobody");
                chat.raw(null, Component.text("nobody"));
                chat.blank(null);
                chat.broadcast((Collection<Audience>) null, "nobody");
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a plugin with no server behind it does not throw when it broadcasts")
        void survivesWithoutAudiences() {
            Chat detached = new Chat(new Brand("RSC"), null);
            assertThatCode(() -> {
                detached.broadcast("into the void");
                detached.console("into the void");
            }).doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------ the three colours

    /**
     * The same words through all three, so the only thing that can differ is the colour.
     *
     * <p>Written this way after a first attempt walked the tree looking for "the first child with a
     * colour" and found the chat prefix's grey chevron every time — three identical answers for
     * three different messages. Comparing whole components asks the question directly and cannot be
     * fooled by where in the tree the colour happens to sit.
     */
    @Test
    @DisplayName("yes, careful and no are three different colours, all from the palette")
    void colourfulMessagesDiffer() {
        chat.ok(alice, "Something happened");
        chat.warn(alice, "Something happened");
        chat.no(alice, "Something happened");

        List<Component> sent = alice.received;
        assertThat(sent).hasSize(3);
        assertThat(sent.stream().map(ChatTest::plain))
                .as("the words are identical; only the colour may differ")
                .containsOnly("RSC » Something happened");
        assertThat(sent).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a blank line is a blank line, not the word null")
    void sendsBlankLines() {
        chat.blank(alice);
        assertThat(plain(alice.only())).isEmpty();
    }

    // ------------------------------------------------------------------ fakes

    /** Stands in for a player: remembers what they were told. */
    private static final class Recorder implements Audience {
        private final List<Component> received = new ArrayList<>();

        @Override
        public void sendMessage(Component message) {
            received.add(message);
        }

        Component only() {
            assertThat(received).hasSize(1);
            return received.getFirst();
        }
    }

    private record FakeServer(Collection<? extends Audience> players, Audience consoleSender)
            implements Audiences {

        @Override
        public Collection<? extends Audience> everyone() {
            return players;
        }

        @Override
        public Audience console() {
            return consoleSender;
        }
    }
}
