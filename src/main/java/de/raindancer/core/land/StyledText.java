package de.raindancer.core.land;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;
import java.util.Optional;

/**
 * A single line of claim owner authored text with a colour and the four decorations the plan asks for.
 * <p>
 * The raw text is stored verbatim and never interpreted as MiniMessage, so a player cannot smuggle
 * click events or hover tooltips into a title. Styling is applied programmatically.
 */
public final class StyledText {

    private String raw;
    private NamedTextColor color;
    private boolean bold;
    private boolean italic;
    private boolean underlined;
    private boolean strikethrough;
    private boolean obfuscated;

    public StyledText(String raw, NamedTextColor color, boolean bold, boolean italic,
                      boolean underlined, boolean strikethrough, boolean obfuscated) {
        this.raw = raw == null ? "" : raw;
        this.color = color == null ? NamedTextColor.WHITE : color;
        this.bold = bold;
        this.italic = italic;
        this.underlined = underlined;
        this.strikethrough = strikethrough;
        this.obfuscated = obfuscated;
    }

    public static StyledText of(String raw) {
        return new StyledText(raw, NamedTextColor.WHITE, false, false, false, false, false);
    }

    public static StyledText empty() {
        return of("");
    }

    public String raw() {
        return raw;
    }

    public void raw(String raw) {
        this.raw = raw == null ? "" : raw;
    }

    public NamedTextColor color() {
        return color;
    }

    public void color(NamedTextColor color) {
        this.color = color == null ? NamedTextColor.WHITE : color;
    }

    public boolean bold() {
        return bold;
    }

    public boolean italic() {
        return italic;
    }

    public boolean underlined() {
        return underlined;
    }

    public boolean strikethrough() {
        return strikethrough;
    }

    public boolean obfuscated() {
        return obfuscated;
    }

    public void bold(boolean value) {
        this.bold = value;
    }

    public void italic(boolean value) {
        this.italic = value;
    }

    public void underlined(boolean value) {
        this.underlined = value;
    }

    public void strikethrough(boolean value) {
        this.strikethrough = value;
    }

    public void obfuscated(boolean value) {
        this.obfuscated = value;
    }

    public boolean isBlank() {
        return raw.isBlank();
    }

    /**
     * The line as a component, with every decoration stated explicitly.
     *
     * <p>Each decoration is set rather than left alone, including the ones that are off. A component that
     * merely omits {@code italic} inherits it from wherever it is rendered — and item lore is italic by
     * default in Minecraft, so a title an owner deliberately left un-italic would come out italic in
     * every menu that shows it.
     *
     * <p>Inlined rather than borrowed from a text utility: this is the only caller, and a second
     * "decorate a string" helper next to {@code Chat} and {@code Style} would be a third opinion about
     * the same thing.
     */
    public Component render() {
        return Component.text(raw)
                .color(color)
                .decoration(TextDecoration.BOLD, bold)
                .decoration(TextDecoration.ITALIC, italic)
                .decoration(TextDecoration.UNDERLINED, underlined)
                .decoration(TextDecoration.STRIKETHROUGH, strikethrough)
                .decoration(TextDecoration.OBFUSCATED, obfuscated);
    }

    public StyledText copy() {
        return new StyledText(raw, color, bold, italic, underlined, strikethrough, obfuscated);
    }

    public String colorKey() {
        return color.toString().toLowerCase(Locale.ROOT);
    }

    /** Resolves one of the 16 vanilla colour names; unknown input yields an empty optional. */
    public static Optional<NamedTextColor> colorByName(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        NamedTextColor color = NamedTextColor.NAMES.value(raw.trim().toLowerCase(Locale.ROOT).replace('-', '_'));
        return Optional.ofNullable(color);
    }
}
