package de.raindancer.core.chat;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Everything a plugin here says to a player, and the only way it says it.
 *
 * <h2>Why this exists</h2>
 * There were five of these: {@code Notifier}, claims' {@code Messages}, RRP's {@code Msg}, and a
 * {@code Chrome} class copied three times between homes, TPA and the ghast lines. Each had its own
 * idea of what a prefix was, whether a message went to chat or above the hotbar, and which of them
 * escaped player-supplied text before pasting it into MiniMessage — the last one being a formatting
 * injection waiting to happen, since a home called {@code <red>} was being parsed as markup by two
 * of the five. One implementation, and the plugins go back to deciding *what* to say.
 *
 * <h2>tell versus say</h2>
 * The distinction is the whole reason this is not one method:
 * <ul>
 *   <li>{@link #tell} is an <em>answer</em> — "You may not build here", "Home set". It concerns
 *       nobody but the player who caused it, it is read once, and it goes above the hotbar when the
 *       server has asked for that, where it appears where the player is already looking and then
 *       leaves on its own.</li>
 *   <li>{@link #say} is something meant to be <em>read</em> — a list, a heading, a page of a manual.
 *       It always goes to chat, because the action bar shows one line for a few seconds and a list
 *       shown that way is a list nobody read.</li>
 * </ul>
 * Getting this wrong is not cosmetic. A list routed through {@code tell} loses every line but the
 * last; an answer routed through {@code say} pushes the conversation off the screen ten times a
 * minute.
 *
 * <h2>Untrusted text</h2>
 * Anything a player typed — a home name, a claim name, a pack's title, another plugin's error
 * message — goes in through {@link #arg}, never concatenated into the template. {@link #arg} uses
 * {@link Placeholder#unparsed}, so a claim called {@code <rainbow>} shows up as those nine
 * characters instead of recolouring the rest of the line, and an unclosed tag cannot swallow the
 * message it was pasted into.
 *
 * <h2>Thread safety</h2>
 * Building a component is safe from any thread. Sending is Adventure's business and is safe too.
 * Nothing here touches the world, so none of it needs a region thread.
 */
public final class Chat {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Brand brand;

    /** @param brand this plugin's name, as a player sees it */
    public Chat(Brand brand) {
        this.brand = brand == null ? new Brand("Rain") : brand;
    }

    /** Shorthand for a plugin that only wants a tag. */
    public static Chat of(String tag) {
        return new Chat(new Brand(tag));
    }

    public Brand brand() {
        return brand;
    }

    // ------------------------------------------------------------------ safe placeholders

    /**
     * One piece of untrusted text, safe to put in a message.
     *
     * <p>Use it for everything a player or a remote server produced. The value is inserted as text,
     * never as markup.
     */
    public static TagResolver arg(String name, String value) {
        return Placeholder.unparsed(name, value == null ? "" : value);
    }

    /** A number, a count, a duration — anything whose {@code toString} is the text you want. */
    public static TagResolver arg(String name, Object value) {
        return Placeholder.unparsed(name, value == null ? "" : String.valueOf(value));
    }

    /**
     * A piece of text that is <em>meant</em> to carry formatting — a heading built elsewhere, a
     * player's coloured display name.
     *
     * <p>Deliberately harder to reach for than {@link #arg}, and deliberately named for what it
     * costs: everything passed here is parsed. Never hand it something a player typed.
     */
    public static TagResolver formatted(String name, Component value) {
        return Placeholder.component(name, value == null ? Component.empty() : value);
    }

    // ---------------------------------------------------------------------- building

    /** A component from MiniMessage, with no prefix — for a line that goes inside something else. */
    public Component mm(String miniMessage, TagResolver... arguments) {
        return MINI.deserialize(miniMessage == null ? "" : miniMessage, arguments);
    }

    /** The same, signed with this plugin's tag. */
    public Component prefixed(String miniMessage, TagResolver... arguments) {
        return MINI.deserialize(brand.chatPrefix() + (miniMessage == null ? "" : miniMessage),
                arguments);
    }

    // ----------------------------------------------------------------------- answers

    /**
     * An answer to something this recipient just did. May go above the hotbar.
     *
     * <p>Plain — no colour of its own. For the three that mean something, use {@link #ok},
     * {@link #warn} or {@link #no}.
     */
    public void tell(Audience recipient, String miniMessage, TagResolver... arguments) {
        Feedback.personal(recipient, prefixed(miniMessage, arguments));
    }

    /** It worked. */
    public void ok(Audience recipient, String miniMessage, TagResolver... arguments) {
        tell(recipient, colour(Style.ok(), miniMessage), arguments);
    }

    /** It worked, but be careful — or it half worked. */
    public void warn(Audience recipient, String miniMessage, TagResolver... arguments) {
        tell(recipient, colour(Style.warn(), miniMessage), arguments);
    }

    /**
     * No.
     *
     * <p>Named {@code no} rather than {@code error} because that is what it is used for nine times
     * out of ten — a refusal the player caused, not a fault. A fault the player should know about is
     * still this, and a fault they should not is {@link de.raindancer.core.log.Log}'s business.
     */
    public void no(Audience recipient, String miniMessage, TagResolver... arguments) {
        tell(recipient, colour(Style.bad(), miniMessage), arguments);
    }

    // -------------------------------------------------------------------- things to read

    /** Something meant to be read at leisure: a heading, a row of a list, a page. Always chat. */
    public void say(Audience recipient, String miniMessage, TagResolver... arguments) {
        if (recipient == null) {
            return;
        }
        recipient.sendMessage(prefixed(miniMessage, arguments));
    }

    /**
     * A line of a list, with no tag in front of it.
     *
     * <p>A twelve-row list with the plugin's tag on every row is twelve tags and one list. The
     * heading carries the tag; the rows are indented under it.
     */
    public void row(Audience recipient, String miniMessage, TagResolver... arguments) {
        if (recipient == null) {
            return;
        }
        recipient.sendMessage(mm(miniMessage, arguments));
    }

    /** A finished component, unchanged and unprefixed — for a caller that built its own. */
    public void raw(Audience recipient, Component message) {
        if (recipient != null && message != null) {
            recipient.sendMessage(message);
        }
    }

    /** An empty line, for separating paragraphs in chat. Never the action bar. */
    public void blank(Audience recipient) {
        if (recipient != null) {
            recipient.sendMessage(Component.empty());
        }
    }

    // ------------------------------------------------------------------------ everybody

    /** Every player on the server. Always chat: a broadcast nobody can scroll back to is noise. */
    public void broadcast(String miniMessage, TagResolver... arguments) {
        Component message = prefixed(miniMessage, arguments);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
    }

    /** A chosen few — a town's council, the online moderators. */
    public void broadcast(Collection<? extends Audience> recipients, String miniMessage,
                          TagResolver... arguments) {
        if (recipients == null) {
            return;
        }
        Component message = prefixed(miniMessage, arguments);
        for (Audience recipient : recipients) {
            if (recipient != null) {
                recipient.sendMessage(message);
            }
        }
    }

    /**
     * The server console.
     *
     * <p>Distinct from {@link de.raindancer.core.log.Log}: this is a message <em>to the operator</em>
     * — the result of a command they typed at the console — not a record of something that happened.
     * Anything worth keeping goes through the logger instead, and lands in a file.
     */
    public void console(String miniMessage, TagResolver... arguments) {
        Bukkit.getConsoleSender().sendMessage(prefixed(miniMessage, arguments));
    }

    // ------------------------------------------------------------------------ internals

    /**
     * Wraps a message in one of the palette's colours.
     *
     * <p>Around the whole message rather than merged into it, so a template that colours a word of
     * its own keeps that word's colour and everything else takes the outer one.
     */
    private static String colour(String colour, String miniMessage) {
        return "<" + colour + ">" + (miniMessage == null ? "" : miniMessage) + "</" + colour + ">";
    }
}
