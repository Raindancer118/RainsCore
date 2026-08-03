package de.raindancer.core.ui.banner;

import de.raindancer.core.platform.util.FontWidth;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The console splash a plugin prints when it starts.
 *
 * <h2>What it is for</h2>
 * A server owner starting up watches a wall of grey text scroll past. A plugin that introduces
 * itself in that wall — its name, one line saying what it is for, and the handful of facts that
 * answer "did it work?" — is the difference between a server they understand and a list of jars
 * they hope are fine. It costs a few lines once per start.
 *
 * <pre>
 * Banner.of("RainsCore", "core utils for Raindancer118's plugins")
 *         .version(getPluginMeta().getVersion())
 *         .by("Raindancer118")
 *         .fact("Settings", "18 across 4 topics")
 *         .fact("Logs", "plugins/RainsCore/logs")
 *         .took(Duration.ofMillis(elapsed))
 *         .print(getComponentLogger());
 * </pre>
 *
 * <h2>Why the logo is generated</h2>
 * See {@link BlockLetters}: ten plugins hand-drawing their own is nine chances to get it subtly
 * wrong and a redraw every time one is renamed. A plugin that really wants its own can pass one to
 * {@link #logo}.
 *
 * <h2>Why components and not strings</h2>
 * Written against {@link ComponentLogger}, so Paper renders true colour in the console and leaves
 * the logfile free of escape codes. A splash that fills a logfile with {@code ESC[38;2;...} is a
 * splash somebody turns off.
 *
 * <p>Builders are mutable and meant to be used once, at startup. Nothing here is thread-safe,
 * because nothing here has any business being called from two threads.
 */
public final class Banner {

    /**
     * How wide the splash may be.
     *
     * <p>Chosen for the narrowest thing anybody actually reads a server log in — a default terminal
     * at 80 columns — less a little, because a line that ends flush against the edge looks wrapped
     * even when it is not.
     */
    public static final int MAX_WIDTH = 72;

    /** How wide a fact's label is, so the values line up in a column. */
    private static final int LABEL_WIDTH = 14;

    /**
     * The gradient a logo is drawn in: the violet these plugins have always used.
     *
     * <p>Deliberately not read from {@link de.raindancer.core.ui.chat.Style}. The splash is printed
     * during {@code onEnable}, often before settings are loaded, and a banner that changed colour
     * depending on how far startup had got would be a puzzle rather than a feature.
     */
    private static final List<TextColor> GRADIENT = List.of(
            TextColor.color(0xE0CCFF),
            TextColor.color(0xC9A0FF),
            TextColor.color(0xB088F0),
            TextColor.color(0x9A72DC),
            TextColor.color(0x8664C8),
            TextColor.color(0x7C5CBF));

    private static final TextColor LABEL = TextColor.color(0x8B949E);
    private static final TextColor VALUE = TextColor.color(0xE6EDF3);
    private static final TextColor ACCENT = TextColor.color(0xC9A0FF);
    private static final TextColor TROUBLE = TextColor.color(0xF0883E);

    private final String name;
    private final String tagline;
    private final Map<String, String> facts = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private List<String> logo;
    private String version;
    private String author;
    private Duration took;

    private Banner(String name, String tagline) {
        this.name = name == null ? "" : name.trim();
        this.tagline = tagline == null ? "" : tagline.trim();
    }

    /**
     * @param name    the plugin, as a person would say it
     * @param tagline one line saying what it is for, e.g. "core utils for Raindancer118's plugins"
     */
    public static Banner of(String name, String tagline) {
        return new Banner(name, tagline);
    }

    /** A logo of the plugin's own, instead of one drawn from its name. */
    public Banner logo(List<String> art) {
        this.logo = art == null ? null : List.copyOf(art);
        return this;
    }

    public Banner version(String version) {
        this.version = version;
        return this;
    }

    public Banner by(String author) {
        this.author = author;
        return this;
    }

    /** How long startup took. Left off when nobody measured it. */
    public Banner took(Duration elapsed) {
        this.took = elapsed;
        return this;
    }

    /**
     * One thing worth reporting: {@code fact("Claims", "41 loaded")}.
     *
     * <p>These are what turn a splash from decoration into the answer to "did it work?". Keep them
     * to the handful somebody would actually check.
     */
    public Banner fact(String label, String value) {
        if (label != null && !label.isBlank()) {
            facts.put(label.trim(), value == null ? "" : value.trim());
        }
        return this;
    }

    /**
     * Something that did not work, printed where somebody will see it.
     *
     * <p>A plugin that came up half-working must say so at startup. Written down in the log as well,
     * but the log is what somebody reads afterwards and this is what they read now.
     */
    public Banner warning(String warning) {
        if (warning != null && !warning.isBlank()) {
            warnings.add(warning.trim());
        }
        return this;
    }

    // ---------------------------------------------------------------------------- rendering

    /** The finished splash, one component per line. */
    public List<Component> lines() {
        List<Component> printed = new ArrayList<>();
        printed.add(Component.empty());
        printed.addAll(logoLines());
        printed.add(headline());
        if (!tagline.isEmpty()) {
            printed.add(Component.text("  " + clip(tagline, MAX_WIDTH - 2), LABEL)
                    .decoration(TextDecoration.ITALIC, true));
        }
        printed.add(rule());
        for (Map.Entry<String, String> each : facts.entrySet()) {
            printed.add(factLine(each.getKey(), each.getValue()));
        }
        if (took != null) {
            printed.add(factLine("Ready in", took.toMillis() + " ms"));
        }
        for (String warning : warnings) {
            printed.addAll(warningLines(warning));
        }
        printed.add(Component.empty());
        return List.copyOf(printed);
    }

    /** Prints it. The one method a plugin normally calls. */
    public void print(ComponentLogger logger) {
        if (logger == null) {
            return;
        }
        for (Component line : lines()) {
            logger.info(line);
        }
    }

    private List<Component> logoLines() {
        List<String> art = logo != null ? logo : BlockLetters.render(BlockLetters.abbreviate(name));
        List<Component> drawn = new ArrayList<>(art.size());
        for (int row = 0; row < art.size(); row++) {
            String line = clip(art.get(row), MAX_WIDTH - 2);
            // Down the rows rather than across the columns: a gradient per character would fight the
            // letters for attention, and this way each row reads as one stroke.
            TextColor colour = GRADIENT.get(Math.min(row, GRADIENT.size() - 1));
            drawn.add(Component.text("  " + line, colour));
        }
        return drawn;
    }

    /** The plugin's name, and its version if it has one. */
    private Component headline() {
        Component built = Component.text("  " + name, ACCENT).decoration(TextDecoration.BOLD, true);
        if (version != null && !version.isBlank()) {
            built = built.append(Component.text("  v" + version.trim(), LABEL)
                    .decoration(TextDecoration.BOLD, false));
        }
        if (author != null && !author.isBlank()) {
            built = built.append(Component.text("  by " + author.trim(), LABEL)
                    .decoration(TextDecoration.BOLD, false));
        }
        return built;
    }

    private Component rule() {
        return Component.text("  " + "─".repeat(MAX_WIDTH - 4), TextColor.color(0x3A3F45));
    }

    private Component factLine(String label, String value) {
        String padded = label.length() >= LABEL_WIDTH
                ? label.substring(0, LABEL_WIDTH)
                : label + " ".repeat(LABEL_WIDTH - label.length());
        return Component.text("  " + padded, LABEL)
                .append(Component.text(clip(value, MAX_WIDTH - LABEL_WIDTH - 4), VALUE));
    }

    /**
     * A warning, folded onto as many lines as it needs.
     *
     * <p>Folded rather than clipped, unlike a fact: a fact cut short is still a fact, and a warning
     * cut short is a warning nobody can act on.
     */
    private List<Component> warningLines(String warning) {
        List<Component> folded = new ArrayList<>();
        int width = MAX_WIDTH - 6;
        List<String> wrapped = wrap(warning, width);
        for (int index = 0; index < wrapped.size(); index++) {
            String prefix = index == 0 ? "  ! " : "    ";
            folded.add(Component.text(prefix + wrapped.get(index), TROUBLE));
        }
        return folded;
    }

    /** Greedy word wrap. A word longer than the whole width is broken, because it has to be. */
    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            while (word.length() > width) {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (line.isEmpty()) {
                line.append(word);
            } else if (line.length() + 1 + word.length() <= width) {
                line.append(' ').append(word);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * Cuts a line to fit the console.
     *
     * <p>By character count, not by {@link FontWidth}: that measures Minecraft's proportional font,
     * and a console is monospaced. Using the wrong one here would clip a line that fitted and let
     * one through that did not.
     */
    private static String clip(String text, int width) {
        if (text.length() <= width) {
            return text;
        }
        return text.substring(0, Math.max(0, width - 1)) + "…";
    }
}
