package de.raindancer.core.ui.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Putting values into MiniMessage without letting them become markup.
 *
 * <h2>The danger</h2>
 * A message template is markup; a value pasted into it is whatever somebody typed. A death message with
 * an anvil-renamed sword called {@code <click:run_command:'/op Steve'>Excalibur} turns into a live button
 * in the admin's chat. Escaping each value with {@code MiniMessage.escapeTags} is not enough on its own:
 * a value ending in a backslash escapes the escape of the next one, and replacing placeholders one after
 * another re-scans what earlier ones inserted. Both are ways back in.
 *
 * <h2>The rule here</h2>
 * Placeholders are filled in one pass, and each value is inserted as exactly the text it is —
 * backslashes included — unless it is a {@link Markup} (parsed, a decision made in code) or a
 * {@link Component} (inserted as the component it already is). {@link #literal} is the same escape for
 * code that builds markup by hand.
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private Text() {
    }

    /**
     * Text made safe to paste into markup: it renders as exactly these characters, tags and backslashes
     * included, and cannot open, close or escape anything around it.
     */
    public static String literal(Object text) {
        if (text == null) {
            return "";
        }
        if (text instanceof Markup markup) {
            return markup.miniMessage();
        }
        if (text instanceof ComponentLike component) {
            return closed(component.asComponent());
        }
        // Backslashes first: MiniMessage reads "\<" as "a literal <", so a value's own trailing
        // backslash would otherwise escape whatever the template puts after it.
        return MINI.escapeTags(String.valueOf(text).replace("\\", "\\\\"));
    }

    /**
     * {@code component} as MiniMessage that closes every tag it opens, so whatever is appended after it
     * starts unstyled.
     *
     * <p>MiniMessage's serializer leaves the tags still open at the end unclosed, which is harmless for
     * the string alone and wrong the moment something is concatenated: a bold prefix made every
     * message after it bold.
     */
    public static String closed(Component component) {
        String serialized = MINI.serialize(component);
        java.util.ArrayDeque<String> open = new java.util.ArrayDeque<>();
        int i = 0;
        while (i < serialized.length()) {
            char c = serialized.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c != '<') {
                i++;
                continue;
            }
            int end = tagEnd(serialized, i + 1);
            if (end < 0) {
                break;
            }
            String tag = serialized.substring(i + 1, end);
            if (tag.startsWith("/")) {
                open.pollFirst();
            } else if (!tag.endsWith("/")) {
                int colon = tag.indexOf(':');
                open.push(colon < 0 ? tag : tag.substring(0, colon));
            }
            i = end + 1;
        }
        StringBuilder out = new StringBuilder(serialized);
        open.forEach(name -> out.append("</").append(name).append('>'));
        return out.toString();
    }

    /** Index of the {@code >} ending the tag whose name starts at {@code from}, skipping quoted arguments. */
    private static int tagEnd(String markup, int from) {
        char quote = 0;
        for (int i = from; i < markup.length(); i++) {
            char c = markup.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Fills {@code <name>} placeholders in a template, in one pass: what a value inserts is never looked
     * at again, so one value cannot complete or create another's placeholder.
     *
     * @param nameValues name, value, name, value — values are text unless a {@link Markup} or a Component
     * @throws IllegalArgumentException for an odd number of arguments
     */
    public static String fill(String template, Object... nameValues) {
        if (template == null) {
            return "";
        }
        if (nameValues == null || nameValues.length == 0) {
            return template;
        }
        if (nameValues.length % 2 != 0) {
            throw new IllegalArgumentException("placeholders come in pairs of name and value; got "
                    + nameValues.length + " arguments");
        }
        Map<String, Object> values = new HashMap<>();
        for (int at = 0; at + 1 < nameValues.length; at += 2) {
            values.putIfAbsent(String.valueOf(nameValues[at]), nameValues[at + 1]);
        }
        StringBuilder filled = new StringBuilder(template.length() + 32);
        int index = 0;
        while (index < template.length()) {
            char character = template.charAt(index);
            if (character == '\\' && index + 1 < template.length()) {
                // An escape in the template is the template's own; copied as it stands.
                filled.append(character).append(template.charAt(index + 1));
                index += 2;
                continue;
            }
            if (character == '<') {
                int close = template.indexOf('>', index + 1);
                if (close > 0) {
                    String name = template.substring(index + 1, close);
                    if (values.containsKey(name)) {
                        filled.append(literal(values.get(name)));
                        index = close + 1;
                        continue;
                    }
                }
            }
            filled.append(character);
            index++;
        }
        return filled.toString();
    }

    /** A template with its placeholders filled, rendered. Broken markup renders as its plain text. */
    public static Component render(String template, Object... nameValues) {
        String markup = fill(template, nameValues);
        try {
            return MINI.deserialize(markup);
        } catch (RuntimeException broken) {
            return Component.text(markup.replaceAll("<[^>]*>", ""));
        }
    }

    /**
     * Markup that may only <em>look</em> like something: colours, decorations, gradients, rainbows,
     * fonts, shadows, resets. No click, no hover, no insertion, no selector, no translation, no NBT —
     * nothing that does anything or reaches into the server. For text a player chose to style for
     * themselves — a nickname, a prefix, a team tag — which may be pretty and must never be a button.
     * Anything else written as a tag shows up as the characters it is.
     */
    public static Component styled(String miniMessage) {
        if (miniMessage == null || miniMessage.isEmpty()) {
            return Component.empty();
        }
        try {
            return STYLE_ONLY.deserialize(miniMessage);
        } catch (RuntimeException broken) {
            return Component.text(miniMessage);
        }
    }

    /** The parser behind {@link #styled}, for code that needs it directly. */
    public static MiniMessage styleOnly() {
        return STYLE_ONLY;
    }

    private static final MiniMessage STYLE_ONLY = MiniMessage.builder()
            .tags(TagResolver.builder()
                    .resolvers(StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(),
                            StandardTags.rainbow(), StandardTags.reset(), StandardTags.font(),
                            StandardTags.transition(), StandardTags.shadowColor(), StandardTags.pride())
                    .build())
            .build();

    /** Text as a component, never parsed. */
    public static Component plain(String text) {
        return Component.text(text == null ? "" : text);
    }

    /** What a component says, without its formatting — an item's name, say, for a log or a lore line. */
    public static String plain(Component component) {
        return component == null ? "" : PLAIN.serialize(component);
    }
}
