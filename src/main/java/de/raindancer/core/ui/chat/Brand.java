package de.raindancer.core.ui.chat;

import de.raindancer.core.platform.util.FontWidth;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One plugin's name, as a player sees it: the tag in front of its chat messages and on its windows.
 *
 * <h2>Why an object and not a static holder</h2>
 * It used to be static, because everything that needed it lived in one jar. Now the plugins are
 * separate again and each wants its own short tag — {@code Claims}, {@code Homes}, {@code TPA} — while
 * still sharing one {@link Style}. So a brand is an object a plugin makes once and hands to its
 * {@link Chat}: same look everywhere, different name on it.
 *
 * <h2>Why suppliers and not strings</h2>
 * The tag is an admin setting. Holding its value would serve whatever it was at startup for the rest
 * of the server's life; asking per message means a prefix changed in a GUI takes effect on the next
 * line rather than at the next restart, and nothing has to remember to push the change here.
 *
 * <p>Instances are immutable in shape and safe from any thread: the two suppliers are {@code volatile}
 * and only ever replaced wholesale by {@link #configure}.
 */
public final class Brand {

    /** The gradient, the clipping and the chevron are shared; only the tag differs. */
    private static final String CHEVRON = " <dark_gray>»</dark_gray> ";
    /** Between a page and the page it was opened from, so the two separators read as levels. */
    private static final String SUB_CHEVRON = " <dark_gray>\u203a</dark_gray> ";
    private static final String SUB_CHEVRON_PLAIN = " \u203a ";

    /**
     * How wide a chest window's title bar is, in pixels of Minecraft's font.
     *
     * <p>A container title is drawn at x=8 of a 176-pixel-wide texture and is not wrapped, not
     * scrolled and not ellipsised by the client: text that does not fit simply continues past the
     * edge of the window and over whatever is behind it. 154 leaves a little under the 160 available,
     * because a title that ends flush against the frame looks like it was cut off even when it is not.
     *
     * <p>This used to be a count of 30 characters, which is the wrong unit — the font is proportional,
     * so that limit passed both {@code RSC » Gamerules} and {@code YeukSMP » Your claims · by name}
     * while only the second overflowed. What is left for the page is now whatever the configured tag
     * does not already use, which is the other half of the same mistake: a seven-letter bold tag costs
     * three times what a three-letter one does.
     */
    private static final int TITLE_PIXELS = 154;

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final String defaultTag;
    private volatile Supplier<String> tag;
    private volatile BooleanSupplier show;

    /**
     * @param defaultTag what this plugin signs itself with until a server says otherwise, e.g. {@code "RSC"}
     */
    public Brand(String defaultTag) {
        String cleaned = defaultTag == null ? "" : defaultTag.trim();
        this.defaultTag = cleaned.isEmpty() ? "Rain" : cleaned;
        this.tag = () -> this.defaultTag;
        this.show = () -> true;
    }

    /**
     * Points the brand at the plugin's settings. Called once, at startup; returns {@code this} so it
     * can be chained onto the constructor.
     *
     * @param tagSource where the tag comes from; null keeps the default
     * @param shown     whether the chat tag is shown at all; null keeps "yes"
     */
    public Brand configure(Supplier<String> tagSource, BooleanSupplier shown) {
        if (tagSource != null) {
            this.tag = tagSource;
        }
        if (shown != null) {
            this.show = shown;
        }
        return this;
    }

    /** The tag this plugin signs itself with right now, never blank. */
    public String tag() {
        String configured = read();
        return configured == null || configured.isBlank() ? defaultTag : configured.trim();
    }

    /** What the plugin was built with, whatever the server has since set. */
    public String defaultTag() {
        return defaultTag;
    }

    /**
     * What every chat message from this plugin starts with, as MiniMessage.
     *
     * <p>Empty when the prefix is switched off, so a caller can concatenate it unconditionally.
     */
    public String chatPrefix() {
        if (!shown()) {
            return "";
        }
        return gradientTag() + CHEVRON;
    }

    /** The same, ready to prepend to a component. */
    public Component prefix() {
        return MiniMessage.miniMessage().deserialize(chatPrefix());
    }

    /**
     * A window title: the tag, then this page.
     *
     * <p>Unlike the chat prefix it is always shown: a window with no name at all is worse than a
     * window whose name somebody wanted shorter.
     *
     * @param page MiniMessage for the page's own name; blank for the front page
     */
    public Component title(String page) {
        String trimmed = page == null ? "" : page.trim();
        if (trimmed.isEmpty()) {
            return MiniMessage.miniMessage().deserialize(gradientTag());
        }
        return MiniMessage.miniMessage()
                .deserialize(gradientTag() + dash() + clip(trimmed, pageBudget()));
    }

    /**
     * The same, for a screen that has already built its page name as a component.
     *
     * <p>Kept separate rather than serialising the component back to MiniMessage: a round trip through
     * a string would re-parse any player-supplied name — a claim called {@code <red>} — as markup.
     */
    public Component wrap(Component page) {
        if (page == null) {
            return title("");
        }
        return title("").append(MiniMessage.miniMessage().deserialize(dash())).append(clip(page));
    }

    // ------------------------------------------------------------------ internals

    private String read() {
        try {
            return tag.get();
        } catch (RuntimeException broken) {
            return defaultTag;
        }
    }

    private boolean shown() {
        try {
            return show.getAsBoolean();
        } catch (RuntimeException broken) {
            return true;
        }
    }

    /**
     * What separates the tag from the page.
     *
     * <p>The colour comes from {@link Style}: a page name that carries none of its own inherits this,
     * which is how every menu gets its title colour without saying so.
     */
    private static String dash() {
        return "<" + Style.titleLabel() + "> » ";
    }

    /** The tag in the server's gradient — the branded half of a window title or a chat prefix. */
    private String gradientTag() {
        return "<gradient:" + Style.brandFrom() + ":" + Style.brandTo() + "><bold>" + escape(tag())
                + "</bold></gradient>";
    }

    /**
     * Keeps an admin's typed prefix out of the markup.
     *
     * <p>The tag is admin-supplied text being pasted into a MiniMessage string. Escaping means a
     * prefix of {@code <red>} shows up as those five characters rather than silently recolouring —
     * and, more to the point, that an unclosed tag cannot swallow the rest of every message the
     * plugin sends.
     */
    private static String escape(String raw) {
        return MiniMessage.miniMessage().escapeTags(raw);
    }

    /**
     * The pixels left for the page name once the tag and the separator have had theirs.
     *
     * <p>Never less than a little, so an absurd tag shortens the page rather than erasing it.
     */
    /**
     * A window title with the page it was opened from in front of it: {@code RSC » Server › All claims}.
     *
     * <p>A chest menu has no other chrome, so three levels in the title said "Trusted people" and nothing on
     * screen said which claim that belonged to.
     *
     * <p><b>The page wins the budget.</b> Minecraft clips a title by cutting the end off, so simply joining
     * the two names produced {@code Claims » claimtrials › Trusted…} — it spent the space on where you came
     * from and lost where you are, which is the half worth having. So the parent is only included when both
     * fit whole. When they do not, the parent is dropped: a title that says less is better than one that
     * trails off mid-word.
     *
     * @param from  the page this was opened from; null or blank for a front page
     * @param page  this page, which is never sacrificed
     */
    public Component trail(Component from, Component page) {
        Component here = page == null ? Component.empty() : page;
        if (from == null) {
            return wrap(here);
        }

        String parentText = PLAIN.serialize(from);
        String pageText = PLAIN.serialize(here);
        if (parentText.isBlank()) {
            return wrap(here);
        }

        int needed = FontWidth.of(parentText) + FontWidth.of(SUB_CHEVRON_PLAIN) + FontWidth.of(pageText);
        if (needed > pageBudget()) {
            return wrap(here);
        }
        return wrap(from
                .append(MiniMessage.miniMessage().deserialize(SUB_CHEVRON))
                .append(here));
    }

    private int pageBudget() {
        return Math.max(24, TITLE_PIXELS - FontWidth.of(tag(), true) - FontWidth.of(" » ", false));
    }

    /**
     * Shortens an over-long page name to a pixel budget, without cutting a MiniMessage tag in half.
     *
     * <p>Clipping a string that contains markup is how a title ends in {@code "<gr"}, so tags are
     * copied whole and cost nothing: they are instructions to the renderer, not glyphs on the screen.
     */
    static String clip(String page, int pixels) {
        StringBuilder kept = new StringBuilder();
        StringBuilder visible = new StringBuilder();
        boolean inTag = false;
        for (char character : page.toCharArray()) {
            if (character == '<') {
                inTag = true;
            }
            if (inTag) {
                kept.append(character);
                if (character == '>') {
                    inTag = false;
                }
                continue;
            }
            if (FontWidth.of(visible.toString()) + FontWidth.of(character, false) > pixels) {
                return kept + "…";
            }
            kept.append(character);
            visible.append(character);
        }
        return kept.toString();
    }

    /**
     * The same length cap for a page name that arrives as a component.
     *
     * <p>Truncated by walking the tree and keeping each node's own style rather than by flattening to
     * plain text: titles carry colour that distinguishes the subject from the section
     * ("<i>Raindancer118's home</i> ▸ <i>Kicks and bans</i>"), and a clipped title that has lost that
     * distinction is harder to read than a clipped one that has not.
     */
    private Component clip(Component page) {
        int budget = pageBudget();
        if (FontWidth.of(PLAIN.serialize(page)) <= budget) {
            return page;
        }
        return clip(page, new int[] {budget}).append(Component.text("…"));
    }

    /** @param budget one-element array, so the recursion shares a single running count */
    private static Component clip(Component component, int[] budget) {
        Component clipped;
        if (component instanceof TextComponent text) {
            boolean bold = component.hasDecoration(TextDecoration.BOLD);
            String content = FontWidth.fit(text.content(), budget[0], bold);
            budget[0] -= FontWidth.of(content, bold);
            clipped = Component.text(content).style(component.style());
        } else {
            // Anything that is not plain text is atomic here: half a translated key is not a shorter
            // title, it is a broken one.
            budget[0] -= FontWidth.of(PLAIN.serialize(component.children(List.of())));
            clipped = component.children(List.of());
        }
        for (Component child : component.children()) {
            if (budget[0] <= 0) {
                break;
            }
            clipped = clipped.append(clip(child, budget));
        }
        return clipped;
    }
}
