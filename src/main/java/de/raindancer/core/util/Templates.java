package de.raindancer.core.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Filling {@code {placeholder}}s in an admin-written line, in one place.
 *
 * <h2>Why this is not just {@code String#replace}</h2>
 * It was, in four places, and it was wrong in the same way in all of them: {@code replace} is
 * case-sensitive, so an admin who wrote <code>{Player}</code> — which is what somebody writing a
 * sentence naturally types — got the literal text {@code {Player}} broadcast to the whole server. The
 * message looked fine in the config and was broken only at the moment it mattered. Placeholders are
 * matched without regard to case here, and that is the entire reason this class exists.
 *
 * <h2>Substituted before parsing, always</h2>
 * Every value is escaped and inserted <em>before</em> the MiniMessage parse, so a player name, a kick
 * reason or a claim name can never introduce markup. A reason of {@code <red>} is five characters, not
 * a colour change — and, more to the point, an unclosed tag in a reason cannot swallow the rest of the
 * screen a banned player is looking at.
 */
public final class Templates {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Templates() {
    }

    /** Starts a set of placeholder values. */
    public static Values values() {
        return new Values();
    }

    /** Shorthand for the common one-placeholder case. */
    public static String fill(String template, String name, String value) {
        return values().with(name, value).into(template);
    }

    /** Shorthand for a template with one placeholder, rendered. */
    public static Component render(String template, String name, String value) {
        return values().with(name, value).render(template);
    }

    /** A set of placeholder names and what to put in their place. */
    public static final class Values {

        /** Insertion-ordered so the substitution order is predictable when debugging. */
        private final Map<String, String> values = new LinkedHashMap<>();

        private Values() {
        }

        /**
         * @param name  the placeholder, without braces — {@code "player"}, not {@code "{player}"}
         * @param value inserted escaped; null becomes empty
         */
        public Values with(String name, String value) {
            values.put(name.toLowerCase(Locale.ROOT), value == null ? "" : value);
            return this;
        }

        /** As {@link #with}, but leaves the value unescaped — only for text this plugin wrote itself. */
        public Values withMarkup(String name, String miniMessage) {
            values.put(name.toLowerCase(Locale.ROOT) + "!", miniMessage == null ? "" : miniMessage);
            return this;
        }

        /** The template with every placeholder filled, still as MiniMessage. */
        public String into(String template) {
            if (template == null) {
                return "";
            }
            StringBuilder result = new StringBuilder(template.length());
            int index = 0;
            while (index < template.length()) {
                char character = template.charAt(index);
                if (character != '{') {
                    result.append(character);
                    index++;
                    continue;
                }
                int close = template.indexOf('}', index);
                if (close < 0) {
                    // An unclosed brace is text, not a broken placeholder: an admin writing prose about
                    // "{" should see what they typed rather than lose the rest of the line.
                    result.append(template.substring(index));
                    break;
                }
                String name = template.substring(index + 1, close).trim().toLowerCase(Locale.ROOT);
                if (values.containsKey(name)) {
                    result.append(MINI.escapeTags(values.get(name)));
                } else if (values.containsKey(name + "!")) {
                    result.append(values.get(name + "!"));
                } else {
                    // Unknown placeholder: left exactly as written, so a typo is visible instead of
                    // silently vanishing. An admin can see {plyer} and fix it.
                    result.append(template, index, close + 1);
                }
                index = close + 1;
            }
            return result.toString();
        }

        /** The template filled and parsed. */
        public Component render(String template) {
            return MINI.deserialize(into(template));
        }
    }
}
