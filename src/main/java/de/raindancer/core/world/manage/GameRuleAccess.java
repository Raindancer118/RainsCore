package de.raindancer.core.world.manage;

import org.bukkit.World;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A world's game rules as names and text, over the typed API. Since Minecraft 26.3 game rules are
 * registry entries with snake_case names, and Paper removes the old by-name string calls — so the
 * text form a {@link WorldSnapshot} keeps is translated here, in one place.
 */
interface GameRuleAccess {

    /** Every rule the server knows, with this world's value, by name. */
    Map<String, String> read(World world);

    /** The type of the rule called {@code name}, or empty when this server has no such rule. */
    Optional<Class<?>> typeOf(String name);

    /** Sets {@code name} to {@code value}, which is already of {@link #typeOf}'s type. */
    void write(World world, String name, Object value);

    /** Text as a value of {@code type}: a switch or a whole number — the only kinds of rule there are. */
    static Optional<Object> parse(Class<?> type, String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim().toLowerCase(Locale.ROOT);
        if (type == Boolean.class) {
            return trimmed.equals("true") || trimmed.equals("false")
                    ? Optional.of(Boolean.parseBoolean(trimmed)) : Optional.empty();
        }
        if (type == Integer.class) {
            try {
                return Optional.of(Integer.parseInt(trimmed));
            } catch (NumberFormatException notAWholeNumber) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
