package de.raindancer.core.settings;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turning what is in a YAML file, or what somebody typed at a command, into a setting's real type —
 * and back into something readable.
 *
 * <h2>Why reading a file and reading a command are not the same</h2>
 * They fail differently, so they are allowed to behave differently, and {@link SettingsStore} uses
 * this class in both modes on purpose:
 *
 * <ul>
 *   <li><b>A file</b> was edited months ago by somebody who has gone to bed. A height of 900 where
 *       the maximum is 16 has to become 16 and be noted, because refusing to start the server over
 *       it helps nobody, and resetting it to the default silently is worse — it throws away what
 *       they meant.</li>
 *   <li><b>A command</b> was typed five seconds ago by somebody standing right there. 900 is
 *       refused and they are told the range, because they can fix it and a value silently changed
 *       under them is how a server owner stops trusting the plugin.</li>
 * </ul>
 */
final class SettingCodec {

    private SettingCodec() {
    }

    /**
     * Reads a raw YAML value into a setting's type, or empty when it cannot be read at all.
     *
     * <p>Empty means "this is not that kind of thing" — a word where a number goes, a colour nobody
     * has heard of. Out-of-range numbers are <em>not</em> empty; see {@link #clamp}.
     */
    static Optional<Object> fromYaml(Setting<?> setting, Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Class<?> type = setting.type();
        if (type == Boolean.class) {
            return raw instanceof Boolean flag ? Optional.of(flag) : Optional.empty();
        }
        if (type == Integer.class) {
            return raw instanceof Number number ? Optional.of(number.intValue()) : Optional.empty();
        }
        if (type == Long.class) {
            return raw instanceof Number number ? Optional.of(number.longValue()) : Optional.empty();
        }
        if (type == Double.class) {
            return raw instanceof Number number ? Optional.of(number.doubleValue()) : Optional.empty();
        }
        if (type == String.class) {
            return Optional.of(String.valueOf(raw));
        }
        if (type == List.class) {
            return Optional.of(asStrings(raw));
        }
        return fromText(setting, String.valueOf(raw));
    }

    /**
     * Reads text somebody typed. Same as {@link #fromYaml} for the types YAML does not already
     * give a Java type to, and additionally parses numbers and flags out of their spelling.
     */
    static Optional<Object> fromText(Setting<?> setting, String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim();
        if (text.isEmpty() && setting.type() != String.class) {
            return Optional.empty();
        }
        Class<?> type = setting.type();
        try {
            if (type == Boolean.class) {
                return parseBoolean(text);
            }
            if (type == Integer.class) {
                return Optional.of(Integer.parseInt(text));
            }
            if (type == Long.class) {
                return Optional.of(Long.parseLong(text));
            }
            if (type == Double.class) {
                return Optional.of(Double.parseDouble(text));
            }
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
        if (type == String.class) {
            return Optional.of(text);
        }
        if (type == List.class) {
            // Comma-separated, because a command cannot carry a YAML list.
            List<String> items = new ArrayList<>();
            for (String piece : text.split(",")) {
                String trimmed = piece.trim();
                if (!trimmed.isEmpty()) {
                    items.add(trimmed);
                }
            }
            return Optional.of(List.copyOf(items));
        }
        if (type == Material.class) {
            Material material = Material.matchMaterial(text);
            return material == null ? Optional.empty() : Optional.of(material);
        }
        if (type == NamedTextColor.class) {
            NamedTextColor colour = NamedTextColor.NAMES.value(text.toLowerCase(Locale.ROOT));
            return colour == null ? Optional.empty() : Optional.of(colour);
        }
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(text)) {
                    return Optional.of(constant);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Only the two spellings that are unambiguous.
     *
     * <p>Not {@code Boolean.parseBoolean}, which answers "false" to everything it does not
     * recognise — so a mistyped {@code ture} would switch a feature off and say nothing.
     */
    private static Optional<Object> parseBoolean(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "enabled" -> Optional.of(Boolean.TRUE);
            case "false", "off", "no", "disabled" -> Optional.of(Boolean.FALSE);
            default -> Optional.empty();
        };
    }

    /** Whether a parsed number is inside the setting's declared range. */
    static boolean isInRange(Setting<?> setting, Object value) {
        if (setting.min() == null || !(value instanceof Number number)) {
            return true;
        }
        double actual = number.doubleValue();
        return actual >= setting.min() && actual <= setting.max();
    }

    /** The same number pulled back inside the range. Used when reading a file, never a command. */
    static Object clamp(Setting<?> setting, Object value) {
        if (setting.min() == null || !(value instanceof Number number)) {
            return value;
        }
        double bounded = Math.max(setting.min(), Math.min(setting.max(), number.doubleValue()));
        Class<?> type = setting.type();
        if (type == Integer.class) {
            return (int) bounded;
        }
        if (type == Long.class) {
            return (long) bounded;
        }
        return bounded;
    }

    /** What goes in the file. Enums, materials and colours are written as their lower-case names. */
    static Object toYaml(Object value) {
        if (value instanceof NamedTextColor colour) {
            return NamedTextColor.NAMES.key(colour);
        }
        if (value instanceof Enum<?> constant) {
            return constant.name().toLowerCase(Locale.ROOT);
        }
        if (value instanceof Material material) {
            return material.name();
        }
        return value;
    }

    /** What a person reads: {@code on}, {@code 3}, {@code ask}, {@code nether, the_end}. */
    static String display(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean flag) {
            return flag ? "on" : "off";
        }
        if (value instanceof List<?> items) {
            return String.join(", ", items.stream().map(String::valueOf).toList());
        }
        return String.valueOf(toYaml(value));
    }

    /**
     * The next value round, for a click on a button.
     *
     * <p>Flags flip and choices advance, wrapping. Anything else has no "next" — a number could go
     * up by one or by a hundred and a string has nowhere to go — so it is returned unchanged and the
     * caller asks properly instead.
     */
    static Object cycle(Setting<?> setting, Object current) {
        if (setting.type() == Boolean.class) {
            return !Boolean.TRUE.equals(current);
        }
        if (setting.type().isEnum()) {
            Object[] constants = setting.type().getEnumConstants();
            for (int index = 0; index < constants.length; index++) {
                if (constants[index].equals(current)) {
                    return constants[(index + 1) % constants.length];
                }
            }
            return constants.length == 0 ? current : constants[0];
        }
        return current;
    }

    /** A YAML value as a list of strings, tolerating a single scalar where a list was meant. */
    private static List<String> asStrings(Object raw) {
        if (raw instanceof List<?> items) {
            List<String> strings = new ArrayList<>(items.size());
            for (Object item : items) {
                strings.add(String.valueOf(item));
            }
            return List.copyOf(strings);
        }
        return List.of(String.valueOf(raw));
    }
}
