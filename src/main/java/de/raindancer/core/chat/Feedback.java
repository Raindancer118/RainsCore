package de.raindancer.core.chat;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.util.function.BooleanSupplier;

/**
 * Where a message that concerns nobody but the player who caused it is put.
 *
 * <h2>Why the action bar</h2>
 * "You may not build here", "Home set", "Speed does nothing on this server" are answers to something
 * the player just did. In chat they push the conversation up the screen and stay there; above the
 * hotbar they appear where the player is already looking and then go away on their own. Broadcasts,
 * lists and manuals are not this — they are meant to be read at leisure, and they stay in chat.
 *
 * <h2>Why it is static, and why it takes a finished component</h2>
 * The same reasoning as {@link Brand}: every message in this jar is built by
 * one of four message classes spread across ~40k lines of vendored code that has no reference to this
 * plugin's settings. A static holder installed once at startup is what lets all four route through
 * here with a one-word edit at each call site.
 * <p>
 * The component arrives already signed with {@link Brand#chatPrefix()} and
 * is passed on unchanged. Stripping the tag for the action bar was the other option and was rejected:
 * it would have made "every message this plugin sends carries the tag" untrue in exactly the case a
 * player sees most often, and the tag costs about a fifth of the width budget below.
 *
 * <h2>Why there is no width fallback any more</h2>
 * There used to be one: a message wider than chat, or one with a line break in it, went to chat instead.
 * It was well meant and it was wrong. A flight's commentary is a series of related lines — "Departing for
 * market", "Bessie has arrived at the north mine — 8s to get on or off" — and the second of those is
 * longer than the first, so one appeared above the hotbar and the next in chat. From a player's seat that
 * is not a careful rule, it is a coin toss, and it makes the plugin look broken in a way no single message
 * ever could.
 *
 * <p>So the setting is now obeyed: on means the action bar, for everything that comes through here. What
 * used to be the two exceptions are handled without moving the message somewhere else instead:
 * <ul>
 *   <li><b>A line break</b> is flattened to a separator, so a two-line message reads as one line rather
 *       than losing its second half.</li>
 *   <li><b>An empty message</b> is still not sent to the action bar, because there is nothing to show and
 *       it would only wipe whatever was there.</li>
 * </ul>
 * A message too wide for the screen is now the caller's problem to keep short, which is the right place
 * for it: the caller knows what it is saying.
 */
public final class Feedback {

    /** What a line break becomes, so a multi-line message can be one line without losing anything. */
    static final String LINE_JOIN = " · ";

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /**
     * Whether personal messages go to the action bar. Installed once at startup from
     * {@code messages.personal-in-action-bar}; a supplier, so flipping the setting in {@code /smpadmin}
     * applies to the next message rather than at the next restart.
     */
    private static volatile BooleanSupplier actionBar = () -> true;

    private Feedback() {
    }

    /** Installed once, at startup, from the plugin's settings. */
    public static void configure(BooleanSupplier useActionBar) {
        if (useActionBar != null) {
            actionBar = useActionBar;
        }
    }

    /**
     * Sends a message that concerns only this recipient.
     *
     * @param recipient anything Adventure can talk to; only a {@link Player} has an action bar
     * @param message   the finished, already-prefixed message
     */
    public static void personal(Audience recipient, Component message) {
        if (recipient == null || message == null) {
            return;
        }
        if (recipient instanceof Player player && actionBar.getAsBoolean() && fitsActionBar(message)) {
            player.sendActionBar(oneLine(message));
            return;
        }
        recipient.sendMessage(message);
    }

    /**
     * Whether this message can be shown above the hotbar at all.
     * <p>
     * Only one thing cannot: nothing. Package-private and taking a component rather than a player, because
     * this is the whole decision and it is worth being able to test it without a server.
     */
    static boolean fitsActionBar(Component message) {
        // An empty line is a spacer between paragraphs in chat. On the action bar it would wipe whatever
        // the previous message put there, which is the opposite of what it is for.
        return !PLAIN.serialize(message).isBlank();
    }

    /**
     * Folds a multi-line message onto one line.
     * <p>
     * The action bar is one line and shows the first of several, silently dropping the rest. Replacing the
     * breaks keeps every word — a wide line is a worse message than a short one, but it is not a message
     * with half of it missing.
     */
    static Component oneLine(Component message) {
        if (PLAIN.serialize(message).indexOf('\n') < 0) {
            return message;
        }
        return message.replaceText(builder -> builder.matchLiteral("\n").replacement(LINE_JOIN));
    }
}
