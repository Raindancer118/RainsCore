package de.raindancer.core.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that make every menu in every plugin read the same way.
 *
 * <h2>Why a source scan</h2>
 * These are rules about how the framework is <em>used</em>, and the mistakes they catch are ones
 * nobody notices until a player says the plugin looks like five plugins. There is no object to
 * assert against — a menu cannot be opened without a server — so this reads the source, the same way
 * the claims module's own layout test did, and for the same reason.
 *
 * <p>It currently guards the framework itself. Once the plugins are migrated onto it, it guards
 * every screen they have: the whole point of one framework is that one test holds all of them.
 */
class MenuGrammarTest {

    private static final Path SOURCES = Path.of("src/main/java");

    private static List<Path> menuSources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/gui/"))
                    .toList();
        }
    }

    /**
     * The bug this prevents: the navigation used to be a method a subclass called at the end of
     * {@code render()}, writing into the bottom row after the content had gone in. A page could
     * overwrite its own Back button without a word, and a paged list did exactly that to its own
     * page arrows.
     */
    @Test
    @DisplayName("no page writes into the chrome row by computing a slot itself")
    void nobodyWritesTheChromeRowByHand() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : menuSources()) {
            if (file.endsWith("Menu.java") || file.endsWith("MenuLayout.java")) {
                // The framework itself is the one thing allowed to write there.
                continue;
            }
            String source = Files.readString(file);
            for (String line : source.lines().toList()) {
                // A literal slot of 45 or more on a set() is the chrome row of a six-row page.
                if (line.contains("set(") && line.matches(".*set\\(\\s*(4[5-9]|5[0-3])\\b.*")) {
                    offenders.add(SOURCES.relativize(file) + ": " + line.strip());
                }
            }
        }
        assertThat(offenders)
                .as("the chrome row is the framework's. Use danger() for the one destructive "
                        + "button; everything else belongs above it.")
                .isEmpty();
    }

    /**
     * A page that overrides {@code handleClick} and forgets {@code super} renders Back and Close and
     * then does nothing when they are clicked — a window with no way out but Escape.
     */
    @Test
    @DisplayName("a page that overrides handleClick still calls super")
    void overridingHandleClickStillCallsSuper() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : menuSources()) {
            String source = Files.readString(file);
            int override = source.indexOf("public void handleClick(");
            if (override < 0 || file.endsWith("Menu.java")) {
                continue;
            }
            String body = source.substring(override,
                    Math.min(source.length(), override + 2000));
            if (!body.contains("super.handleClick(")) {
                offenders.add(SOURCES.relativize(file).toString());
            }
        }
        assertThat(offenders)
                .as("without super, Back and Close are painted and do nothing")
                .isEmpty();
    }

    /**
     * Every window in every plugin wears its plugin's tag, and differs only in the part after it.
     * The claims module learned this the hard way: three home windows were added that built their
     * own titles, and the list beside them said {@code RSC » Your homes} while they said
     * {@code Home — base}.
     */
    @Test
    @DisplayName("no menu builds its own window title instead of going through the brand")
    void everyWindowIsBranded() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : menuSources()) {
            String source = Files.readString(file);
            int at = 0;
            while (true) {
                int call = source.indexOf("createInventory(", at);
                if (call < 0) {
                    break;
                }
                at = call + 1;
                String arguments = source.substring(call,
                        Math.min(source.length(), call + 300));
                if (!arguments.contains("brand.wrap(") && !arguments.contains("brand().wrap(")) {
                    offenders.add(SOURCES.relativize(file).toString());
                }
            }
        }
        assertThat(offenders)
                .as("a window that titles itself will not match the one beside it")
                .isEmpty();
    }

    /**
     * Colours belong to the palette, so a server that changes its theme changes every screen. A
     * hard-coded colour is a screen that ignores the setting, and it is invisible until somebody
     * changes the theme and finds one button that did not move.
     */
    @Test
    @DisplayName("no menu names a colour of its own instead of asking the palette")
    void noHardcodedColours() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : menuSources()) {
            if (file.endsWith("Icons.java")) {
                // Icons is where the palette is read; its fallbacks are the point of it.
                continue;
            }
            String source = Files.readString(file);
            for (String line : source.lines().toList()) {
                String trimmed = line.strip();
                if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                    continue;
                }
                if (trimmed.matches(".*\"<(red|green|blue|yellow|aqua|gold|white|gray|dark_gray"
                        + "|dark_red|dark_green|light_purple|#[0-9A-Fa-f]{6})>.*")) {
                    offenders.add(SOURCES.relativize(file) + ": " + trimmed);
                }
            }
        }
        assertThat(offenders)
                .as("ask Style for the colour, so changing the theme changes this too")
                .isEmpty();
    }

    @Test
    @DisplayName("the framework does not reach for Bukkit's scheduler behind Scheduling's back")
    void noRawScheduler() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : menuSources()) {
            if (Files.readString(file).contains("Bukkit.getScheduler()")) {
                offenders.add(SOURCES.relativize(file).toString());
            }
        }
        assertThat(offenders)
                .as("Bukkit's scheduler does not exist on Folia; use Scheduling")
                .isEmpty();
    }
}
