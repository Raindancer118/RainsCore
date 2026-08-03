package de.raindancer.core.ui.identity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Who a player is, as everybody else sees them: their prefix, their suffix, their colour, and what
 * floats above their head.
 *
 * <h2>Why chat and the nametag are separate</h2>
 * They look the same and they are not. A chat line is a component the server builds and can make as
 * long as it likes. A nametag is rendered by the client above a moving head, has no room for a
 * sentence, and — on a vanilla client — has one line. So a player can carry a rank prefix in both,
 * a decorative suffix in chat only, and a short tag above their head, and the two are set
 * independently.
 */
class IdentitiesTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private Database openedDatabase;

    /** One database per test, opened on first use so the temporary directory already exists. */
    private Database database() {
        if (openedDatabase == null || !openedDatabase.isUsable()) {
            openedDatabase = Database.open(directory.resolve("core.db"), CoreSchema.CORE,
                    () -> false);
        }
        return openedDatabase;
    }

    @org.junit.jupiter.api.AfterEach
    void closeDatabase() {
        if (openedDatabase != null) {
            openedDatabase.close();
        }
    }

    @TempDir
    Path directory;
    private Identities identities;

    @BeforeEach
    void setUp() {
        identities = new Identities(database());
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // ------------------------------------------------------------------ chat

    @Nested
    @DisplayName("in chat")
    class InChat {

        @Test
        @DisplayName("a plain player is just their name")
        void plainByDefault() {
            assertThat(plain(identities.chatName(ALICE, "Raindancer118"))).isEqualTo("Raindancer118");
        }

        @Test
        @DisplayName("a prefix goes in front and a suffix behind")
        void wrapsTheName() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setSuffix(ALICE, " <gray>*");

            assertThat(plain(identities.chatName(ALICE, "Raindancer118")))
                    .isEqualTo("[Admin] Raindancer118 *");
        }

        @Test
        @DisplayName("a colour recolours the name without touching the prefix")
        void coloursTheName() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setColour(ALICE, "aqua");

            Component name = identities.chatName(ALICE, "Raindancer118");
            assertThat(plain(name)).isEqualTo("[Admin] Raindancer118");
            assertThat(name.children()).isNotEmpty();
        }

        @Test
        @DisplayName("a name a player did not choose is never parsed as markup")
        void doesNotParseTheName() {
            assertThat(plain(identities.chatName(ALICE, "<red>notacolour")))
                    .as("a player called <red> is nine characters, not a colour")
                    .isEqualTo("<red>notacolour");
        }
    }

    // ------------------------------------------------------------------ nametags

    @Nested
    @DisplayName("above their head")
    class Nametag {

        @Test
        @DisplayName("uses the nametag prefix, not the chat one")
        void usesItsOwnPrefix() {
            identities.setPrefix(ALICE, "<gold>[Administrator of the realm] ");
            identities.setNametagPrefix(ALICE, "<gold>[A] ");

            assertThat(plain(identities.nametag(ALICE, "Raindancer118")))
                    .as("a chat prefix is a phrase; a nametag has room for a badge")
                    .isEqualTo("[A] Raindancer118");
        }

        @Test
        @DisplayName("falls back to the chat prefix when no nametag one is set")
        void fallsBackToTheChatPrefix() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            assertThat(plain(identities.nametag(ALICE, "Raindancer118"))).isEqualTo("[Admin] Raindancer118");
        }

        @Test
        @DisplayName("is kept short, because the client draws it over the world")
        void isClipped() {
            identities.setNametagPrefix(ALICE, "<gold>" + "x".repeat(200));
            assertThat(plain(identities.nametag(ALICE, "Raindancer118")).length())
                    .isLessThanOrEqualTo(Identities.MAX_NAMETAG_CHARS);
        }

        @Test
        @DisplayName("a second line can be set and cleared")
        void carriesASecondLine() {
            assertThat(identities.subtitle(ALICE)).isEmpty();
            identities.setSubtitle(ALICE, "<gray>0 blocks");
            assertThat(plain(identities.subtitle(ALICE).orElseThrow())).isEqualTo("0 blocks");
            identities.setSubtitle(ALICE, null);
            assertThat(identities.subtitle(ALICE)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ safety

    @Nested
    @DisplayName("what a prefix may contain")
    class Safety {

        @Test
        @DisplayName("markup is allowed, because that is the point of a prefix")
        void allowsColours() {
            assertThat(identities.setPrefix(ALICE, "<gradient:gold:yellow>[VIP]</gradient> ")).isTrue();
        }

        /**
         * MiniMessage does not throw on a tag it has never heard of — it renders it as text. So a
         * prefix of {@code <notatag>[Oops] } would be stored happily and then appear, literally,
         * in front of that player's name for ever. Whoever typed it should be told now.
         */
        @Test
        @DisplayName("a tag MiniMessage does not know is refused, not rendered as text")
        void refusesUnknownTags() {
            assertThat(identities.setPrefix(ALICE, "<notatag>[Oops] ")).isFalse();
            assertThat(identities.prefix(ALICE)).isEmpty();
        }

        @Test
        @DisplayName("an unclosed colour is fine, because that is how prefixes are written")
        void allowsUnclosedColours() {
            assertThat(identities.setPrefix(ALICE, "<gold>[Admin] ")).isTrue();
        }

        @Test
        @DisplayName("the space at the end of a prefix is kept, because it does the separating")
        void keepsTrailingSpace() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            assertThat(plain(identities.chatName(ALICE, "Raindancer118")))
                    .as("trimming it glues every rank to every name")
                    .isEqualTo("[Admin] Raindancer118");
        }

        @Test
        @DisplayName("an absurdly long prefix is refused")
        void refusesOverlongPrefixes() {
            assertThat(identities.setPrefix(ALICE, "x".repeat(500))).isFalse();
        }

        @Test
        @DisplayName("clearing sets it back to nothing")
        void clears() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setPrefix(ALICE, null);
            assertThat(identities.prefix(ALICE)).isEmpty();
        }

        @Test
        @DisplayName("nulls everywhere are survivable")
        void survivesNulls() {
            assertThatCode(() -> {
                identities.setPrefix(null, "x");
                identities.setColour(ALICE, null);
                identities.chatName(null, null);
                identities.nametag(null, null);
            }).doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------ persistence

    @Nested
    @DisplayName("across a restart")
    class Persistence {

        @Test
        @DisplayName("everything a player was given is still theirs")
        void roundTrips() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setSuffix(ALICE, " <gray>*");
            identities.setNametagPrefix(ALICE, "<gold>[A] ");
            identities.setColour(ALICE, "aqua");
            identities.setSubtitle(ALICE, "<gray>0 blocks");
            identities.setPrefix(BOB, "<green>[Member] ");
            identities.flush();

            // Closed and reopened over the same file, because that is what a restart is: reusing the
            // open connection would prove only that the in-memory copy is still there.
            openedDatabase.close();
            Identities reopened = new Identities(database());
            reopened.load();

            assertThat(plain(reopened.chatName(ALICE, "Raindancer118"))).isEqualTo("[Admin] Raindancer118 *");
            assertThat(plain(reopened.nametag(ALICE, "Raindancer118"))).isEqualTo("[A] Raindancer118");
            assertThat(reopened.colour(ALICE)).contains("aqua");
            assertThat(plain(reopened.subtitle(ALICE).orElseThrow())).isEqualTo("0 blocks");
            assertThat(plain(reopened.chatName(BOB, "Bentex_OG"))).isEqualTo("[Member] Bentex_OG");
        }

        @Test
        @DisplayName("a player with nothing set is not written at all")
        void doesNotStoreEmptyPlayers() {
            identities.setPrefix(ALICE, "<gold>[Admin] ");
            identities.setPrefix(ALICE, null);
            identities.flush();

            openedDatabase.close();
            Identities reopened = new Identities(database());
            reopened.load();
            assertThat(reopened.known()).isEmpty();
        }

        @Test
        @DisplayName("a database with nothing in it is an empty set of identities, not a failure")
        void survivesAMissingFile() {
            Identities fresh = new Identities(Database.open(
                    directory.resolve("never-used.db"), CoreSchema.CORE, () -> false));
            assertThatCode(fresh::load).doesNotThrowAnyException();
            assertThat(fresh.known()).isEmpty();
        }

        @Test
        @DisplayName("nothing is written when nothing changed")
        void doesNotWriteWithoutChanges() {
            identities.load();
            identities.flush();
            assertThat(directory.resolve("identities.yml")).doesNotExist();
        }
    }

    // ------------------------------------------------------------------ symbols

    /**
     * The symbol set: the handful of characters these plugins use for the same things everywhere, so
     * a tick means the same in a menu, in chat and on a sign.
     */
    @Nested
    @DisplayName("the symbol set")
    class TheSymbolSet {

        @Test
        @DisplayName("every symbol has a name that can be looked up")
        void looksUpByName() {
            assertThat(Symbols.of("tick")).isEqualTo(Symbols.TICK);
            assertThat(Symbols.of("TICK")).isEqualTo(Symbols.TICK);
            assertThat(Symbols.of("nothing-like-this")).isEmpty();
        }

        @Test
        @DisplayName("the names are listed, so a command can complete them")
        void listsItsNames() {
            assertThat(Symbols.names()).contains("tick", "cross", "arrow", "star");
        }

        @Test
        @DisplayName("a symbol is one character, so it fits where a character fits")
        void areSingleCharacters() {
            for (String name : Symbols.names()) {
                assertThat(Symbols.of(name))
                        .as("%s", name)
                        .hasSize(1);
            }
        }

        @Test
        @DisplayName("placeholders in a line are replaced")
        void replacesPlaceholders() {
            assertThat(Symbols.expand("Done :tick: and not :cross:"))
                    .isEqualTo("Done " + Symbols.TICK + " and not " + Symbols.CROSS);
        }

        @Test
        @DisplayName("an unknown placeholder is left alone rather than blanked")
        void leavesUnknownPlaceholders() {
            assertThat(Symbols.expand("what is :nonsense: here"))
                    .isEqualTo("what is :nonsense: here");
        }

        @Test
        @DisplayName("text with no placeholders comes back untouched")
        void leavesPlainTextAlone() {
            assertThat(Symbols.expand("nothing to do")).isEqualTo("nothing to do");
            assertThat(Symbols.expand(null)).isEmpty();
        }
    }
}
