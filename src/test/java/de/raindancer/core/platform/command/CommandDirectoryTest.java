package de.raindancer.core.platform.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The directory, and the book built over it.
 *
 * <p>Neither needs a server, which is the point of building it this way: the failures worth catching
 * here are a page that silently overruns and an entry a reader should never have been shown, and both
 * are arithmetic over a list.
 */
class CommandDirectoryTest {

    private static CommandDirectory withSomething() {
        return new CommandDirectory()
                .declare(CommandNote.of("Warps", "warp", "Go to a named place."))
                .declare(CommandNote.of("Moderation", "ban", "Keep somebody off the server.")
                        .needing("rainsmoderation.ban"))
                .declare(CommandNote.of("Homes", "home", "Go to one of your homes.", "<name> — that one"));
    }

    @Nested
    @DisplayName("the directory")
    class Directory {

        @Test
        @DisplayName("a plugin reporting twice replaces rather than doubles")
        void reReporting() {
            // A module that reloads re-reports. Appending would show every command twice, which is
            // the failure a reader would actually notice.
            CommandDirectory directory = new CommandDirectory()
                    .declare(CommandNote.of("Warps", "warp", "Go somewhere."))
                    .declare(CommandNote.of("Warps", "warp", "Go to a named place."));

            assertThat(directory.all()).hasSize(1);
            assertThat(directory.all().get(0).sentence()).isEqualTo("Go to a named place.");
        }

        @Test
        @DisplayName("a command nobody may run is not in somebody's book")
        void whatAReaderIsShown() {
            // Absent, not greyed. Listing /ban to every player teaches every player the staff
            // vocabulary, and the next thirty minutes are people finding out one refusal at a time.
            List<CommandNote> asAPlayer = withSomething().visibleTo(node -> false);

            assertThat(asAPlayer).extracting(CommandNote::command)
                    .containsExactlyInAnyOrder("warp", "home");
            assertThat(withSomething().visibleTo(node -> true)).hasSize(3);
        }

        @Test
        @DisplayName("a module that is switched off leaves the book")
        void forgetting() {
            CommandDirectory directory = withSomething();

            assertThat(directory.forget("Moderation")).isEqualTo(1);
            assertThat(directory.plugins()).containsExactly("Homes", "Warps");
        }

        @Test
        @DisplayName("a slash written into the name is taken either way and stored one way")
        void slashes() {
            assertThat(CommandNote.of("X", "/home", "Go home.").command()).isEqualTo("home");
            assertThat(CommandNote.of("X", "HOME", "Go home.").slashed()).isEqualTo("/home");
            assertThatThrownBy(() -> CommandNote.of("X", "/", "Go home."))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a paragraph where a sentence goes is refused at the source")
        void oneSentence() {
            // Refused where it is written rather than tolerated into a book that overruns three pages
            // later, which is a failure with no error and no obvious cause.
            assertThatThrownBy(() -> CommandNote.of("X", "home", "y".repeat(200)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("one sentence");
        }
    }

    @Nested
    @DisplayName("the book")
    class TheBook {

        /** What a client would actually draw. */
        private String plain(Component page) {
            return PlainTextComponentSerializer.plainText().serialize(page);
        }

        @Test
        @DisplayName("every command reported is somewhere in the book")
        void nothingIsDropped() {
            // The property that matters: a directory that loses a command is worse than no directory,
            // because the reader now believes it does not exist.
            List<Component> pages = new CommandBook(withSomething().all()).pages();
            String everything = pages.stream().map(this::plain).reduce("", String::concat);

            assertThat(everything).contains("/warp", "/ban", "/home");
            assertThat(everything).contains("Go to a named place.", "<name> — that one");
        }

        @Test
        @DisplayName("no page overruns what a client will draw")
        void everyPageFits() {
            // A client truncates an overrunning page silently — no error, the text simply stops. So
            // the longest realistic book is laid out and every page counted.
            List<CommandNote> many = new java.util.ArrayList<>();
            for (int index = 0; index < 40; index++) {
                many.add(CommandNote.of("Moderation", "command" + index,
                        "A sentence about what this one does, of a length somebody would write.",
                        "<somebody> — the usual argument",
                        "list — everything of this kind"));
            }
            for (Component page : new CommandBook(many).pages()) {
                int drawn = 0;
                for (String line : plain(page).split("\n", -1)) {
                    drawn += Math.max(1, (line.length() + 18) / 19);
                }
                assertThat(drawn)
                        .as("a page drawing %d lines is truncated by the client:\n%s", drawn,
                                plain(page))
                        .isLessThanOrEqualTo(14);
            }
        }

        @Test
        @DisplayName("a plugin's stray angle bracket is printed, not parsed")
        void aSentenceIsAValue() {
            // Every sentence in the book came from another plugin's source, and one containing a <
            // would otherwise be a tag — swallowing the rest of the entry at worst.
            List<Component> pages = new CommandBook(List.of(
                    CommandNote.of("X", "why", "Answers <why> it happened."))).pages();
            String everything = pages.stream().map(this::plain).reduce("", String::concat);

            assertThat(everything).contains("Answers <why> it happened.");
        }

        @Test
        @DisplayName("an empty directory is a page saying so, not an empty book")
        void nothingReported() {
            List<Component> pages = new CommandBook(List.of()).pages();

            assertThat(pages).hasSize(1);
            assertThat(plain(pages.get(0))).contains("Nothing on this server");
        }

        @Test
        @DisplayName("the sections are the plugins, and each is headed")
        void sections() {
            List<Component> pages = new CommandBook(withSomething().all()).pages();
            Set<String> headings = Set.of("Homes", "Moderation", "Warps");

            String everything = pages.stream().map(this::plain).reduce("", String::concat);
            assertThat(everything).contains(headings);
        }
    }
}
