package de.raindancer.core.ui.banner;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The console splash every one of these plugins prints when it starts.
 *
 * <h2>Why a plugin should not have to draw its own</h2>
 * The one this replaces had its logo typed out as six lines of box-drawing characters in a constant,
 * which is fine for one plugin and absurd for ten — nine more hand-drawn logos, each subtly
 * misaligned, each needing redrawing when the plugin is renamed. So the letters are drawn from the
 * plugin's name, and a plugin that wants something of its own can still supply it.
 *
 * <p>What is asserted here is mostly shape: that the name is legible in the output, that a tagline
 * and the facts appear, that nothing throws on the odd inputs a plugin name can actually have, and
 * that the whole thing stays inside a console's width.
 */
class BannerTest {

    private static final String NAME = "RainsCore";
    private static final String TAGLINE = "core utils for Raindancer118's plugins";

    private static List<String> render(Banner banner) {
        return banner.lines().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
    }

    private static Banner core() {
        return Banner.of(NAME, TAGLINE)
                .version("1.0.0")
                .by("Raindancer118");
    }

    // ------------------------------------------------------------------ the logo

    @Nested
    @DisplayName("the logo")
    class Logo {

        @Test
        @DisplayName("is drawn from the plugin's name, so nobody has to hand-draw one")
        void isDrawnFromTheName() {
            List<String> drawn = BlockLetters.render("RSC");
            assertThat(drawn).hasSize(BlockLetters.HEIGHT);
            // Every row is the same width, or the letters do not line up.
            assertThat(drawn.stream().map(String::length).distinct()).hasSize(1);
            // And something was actually drawn.
            assertThat(String.join("", drawn).trim()).isNotEmpty();
        }

        @Test
        @DisplayName("uses the initials when the name is too long to fit a console")
        void shortensALongName() {
            // "RainsResourcepackManager" as block letters would be some 170 columns wide.
            List<String> drawn = BlockLetters.render(BlockLetters.abbreviate(
                    "RainsResourcepackManager"));
            assertThat(drawn.getFirst().length()).isLessThanOrEqualTo(Banner.MAX_WIDTH);
        }

        @Test
        @DisplayName("initials split a run-together name on its capitals")
        void initialsSplitOnCapitals() {
            assertThat(BlockLetters.initialsOf("RainsResourcepackManager")).isEqualTo("RRM");
            assertThat(BlockLetters.initialsOf("RainsCore")).isEqualTo("RC");
            assertThat(BlockLetters.initialsOf("Rains Extended Claims")).isEqualTo("REC");
        }

        @Test
        @DisplayName("a run of capitals stays together, because that is the recognisable part")
        void initialsKeepAcronyms() {
            assertThat(BlockLetters.initialsOf("RainsTPA")).isEqualTo("RTPA");
            assertThat(BlockLetters.initialsOf("RainsSMPCore")).isEqualTo("RSMPC");
        }

        /**
         * The whole name is the better logo when it fits, and for most of these plugins it does —
         * so initials are the fallback rather than the rule.
         */
        @Test
        @DisplayName("a name that fits is drawn in full rather than reduced to initials")
        void keepsNamesThatFit() {
            assertThat(BlockLetters.abbreviate("RainsCore")).isEqualTo("RAINSCORE");
            assertThat(BlockLetters.abbreviate("RSC")).isEqualTo("RSC");
            assertThat(BlockLetters.abbreviate("Homes")).isEqualTo("HOMES");
        }

        @Test
        @DisplayName("a name too wide for a console falls back to its initials")
        void shortensNamesThatDoNotFit() {
            assertThat(BlockLetters.abbreviate("RainsResourcepackManager")).isEqualTo("RRM");
            assertThat(BlockLetters.abbreviate("Rains Extended Claims")).isEqualTo("REC");
        }

        @Test
        @DisplayName("a character the font does not have becomes a blank, not a hole in the rows")
        void survivesUnknownCharacters() {
            List<String> drawn = BlockLetters.render("R?C");
            assertThat(drawn).hasSize(BlockLetters.HEIGHT);
            assertThat(drawn.stream().map(String::length).distinct()).hasSize(1);
        }

        @Test
        @DisplayName("an empty name draws nothing rather than throwing")
        void survivesAnEmptyName() {
            assertThat(BlockLetters.render("")).isEmpty();
            assertThat(BlockLetters.render(null)).isEmpty();
        }

        @Test
        @DisplayName("a plugin can supply its own logo instead")
        void acceptsACustomLogo() {
            Banner banner = Banner.of(NAME, TAGLINE)
                    .logo(List.of("  /\\  ", " /  \\ ", "/____\\"));
            assertThat(render(banner)).anyMatch(line -> line.contains("/____\\"));
        }
    }

    // ------------------------------------------------------------------ what it says

    @Nested
    @DisplayName("what the splash says")
    class Content {

        @Test
        @DisplayName("the plugin's name and what it is for")
        void namesItself() {
            List<String> printed = render(core());
            assertThat(printed).anyMatch(line -> line.contains(NAME));
            assertThat(printed)
                    .as("the one line that says what this plugin is actually for")
                    .anyMatch(line -> line.contains(TAGLINE));
        }

        @Test
        @DisplayName("the version and the author, when given")
        void namesItsVersion() {
            List<String> printed = render(core());
            assertThat(printed).anyMatch(line -> line.contains("1.0.0"));
            assertThat(printed).anyMatch(line -> line.contains("Raindancer118"));
        }

        @Test
        @DisplayName("the facts a plugin wants to report, in the order it added them")
        void listsFacts() {
            Banner banner = core()
                    .fact("Claims", "41 loaded")
                    .fact("Towns", "3 loaded")
                    .fact("Storage", "claims.yml");

            List<String> printed = render(banner);
            int claims = indexOfLineContaining(printed, "Claims");
            int towns = indexOfLineContaining(printed, "Towns");
            int storage = indexOfLineContaining(printed, "Storage");

            assertThat(claims).isNotNegative();
            assertThat(towns).isGreaterThan(claims);
            assertThat(storage).isGreaterThan(towns);
            assertThat(printed).anyMatch(line -> line.contains("41 loaded"));
        }

        @Test
        @DisplayName("how long it took, when it is worth saying")
        void reportsTiming() {
            assertThat(render(core().took(Duration.ofMillis(342))))
                    .anyMatch(line -> line.contains("342"));
        }

        @Test
        @DisplayName("a warning, so a half-working plugin says so where somebody will see it")
        void reportsWarnings() {
            List<String> printed = render(core().warning("The pack server could not be started."));
            assertThat(printed).anyMatch(line -> line.contains("could not be started"));
        }

        @Test
        @DisplayName("a plugin with nothing to report still prints a legible splash")
        void worksWithNothingButAName() {
            List<String> printed = render(Banner.of("Homes", "homes you can get back to"));
            assertThat(printed).isNotEmpty();
            assertThat(printed).anyMatch(line -> line.contains("Homes"));
        }
    }

    // ------------------------------------------------------------------ shape

    @Nested
    @DisplayName("the shape of it")
    class Shape {

        @Test
        @DisplayName("nothing runs off the side of a console")
        void staysInsideAConsole() {
            Banner banner = core()
                    .fact("Something with a rather long name", "and a rather long value too")
                    .warning("A warning long enough that it would certainly wrap if nothing here "
                            + "were folding it onto more than one line, which it should.");

            for (String line : render(banner)) {
                assertThat(line.length())
                        .as("'%s' is %d columns wide", line, line.length())
                        .isLessThanOrEqualTo(Banner.MAX_WIDTH);
            }
        }

        @Test
        @DisplayName("it is coloured, because a wall of grey is what nobody reads")
        void isColoured() {
            assertThat(core().lines())
                    .anyMatch(line -> line.color() != null
                            || line.children().stream().anyMatch(child -> child.color() != null));
        }

        @Test
        @DisplayName("building one twice gives the same thing")
        void isRepeatable() {
            assertThat(render(core())).isEqualTo(render(core()));
        }
    }

    // ------------------------------------------------------------------ misuse

    @Nested
    @DisplayName("odd input")
    class Misuse {

        @Test
        @DisplayName("no name, no tagline, nulls everywhere — still no exception")
        void survivesNulls() {
            assertThatCode(() -> {
                Banner.of(null, null).version(null).by(null).took(null)
                        .fact(null, null).warning(null).logo(null).lines();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a name that is all punctuation does not produce a broken logo")
        void survivesAPunctuationName() {
            assertThatCode(() -> render(Banner.of("!!!", "a plugin"))).doesNotThrowAnyException();
        }
    }

    private static int indexOfLineContaining(List<String> lines, String text) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(text)) {
                return index;
            }
        }
        return -1;
    }
}
