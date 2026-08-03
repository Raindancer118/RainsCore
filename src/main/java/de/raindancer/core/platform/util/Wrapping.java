package de.raindancer.core.platform.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Breaking a sentence into lines that fit in a tooltip.
 *
 * <h2>Why one class for something this small</h2>
 * There were four copies of this loop in the jar — one per GUI framework, plus a second one in the
 * claims module's item builder — and they had already drifted: one prefixed every line with
 * {@code <gray>}, one returned raw strings, one built components, and one silently stopped after eight
 * lines. Four answers to "how does lore wrap here" means a tooltip looks different depending on which
 * screen you are on, for no reason a reader could name. The wrapping itself is now here and the callers
 * differ only in what they wrap the result in.
 *
 * <h2>What it does not do</h2>
 * It counts characters, not pixels. Minecraft's font is not fixed-width, so a line of {@code W}s is
 * wider than a line of {@code i}s and no character count is exactly right; the callers pass a width
 * that looks right for prose. Anything cleverer would need the font metrics, which a plugin does not
 * have. It also does not understand MiniMessage: pass it prose, and colour the lines afterwards, or the
 * tags get counted as if a player could read them.
 */
public final class Wrapping {

    private Wrapping() {
    }

    /**
     * Splits on whitespace and fills lines up to {@code width} characters.
     * <p>
     * A single word longer than the width is left whole rather than cut: breaking
     * {@code Raindancer118} across two lines makes it unreadable, and a slightly wide line does not.
     *
     * @param prose plain text; blank input gives no lines at all rather than one empty one
     */
    public static List<String> wrap(String prose, int width) {
        if (prose == null || prose.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : prose.trim().split("\\s+")) {
            if (!line.isEmpty() && line.length() + word.length() + 1 > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * The same, with every line given a MiniMessage prefix.
     * <p>
     * The common case for lore: {@code wrap(description, 38, "<gray>")}.
     */
    public static List<String> wrap(String prose, int width, String prefix) {
        List<String> lines = new ArrayList<>();
        for (String line : wrap(prose, width)) {
            lines.add(prefix + line);
        }
        return lines;
    }

    /**
     * The same, capped at {@code maxLines}.
     * <p>
     * The last line kept ends in an ellipsis, so a tooltip that has been shortened says so instead of
     * appearing to be the whole text.
     */
    public static List<String> wrap(String prose, int width, String prefix, int maxLines) {
        List<String> lines = wrap(prose, width, prefix);
        if (lines.size() <= maxLines || maxLines <= 0) {
            return lines;
        }
        List<String> capped = new ArrayList<>(lines.subList(0, maxLines));
        capped.set(maxLines - 1, capped.get(maxLines - 1) + "…");
        return capped;
    }
}
