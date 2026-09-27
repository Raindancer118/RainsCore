package de.raindancer.core.ui.chat;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A chat channel a module offers — Manhunt's team chat, say. The chat plugin routes lines through
 * {@link ChatChannels}; the module only says who hears whom.
 */
public interface ChatChannel {

    /** What a player types: {@code /chat team}. Lower case. */
    String id();

    /** How the channel is shown. */
    String label();

    /** Who hears {@code speaker} on this channel right now — empty when they have no part in it now. */
    Optional<Set<UUID>> audienceFor(UUID speaker);

    /** The tag in front of a line, so nobody mistakes team chat for public chat. */
    default String tagFor(UUID speaker) {
        return "[" + label() + "]";
    }

    /**
     * Says {@code text} on this channel itself, instead of the chat plugin — for a channel that has its
     * own format or keeps its own record (staff chat logs to the console). Called on the async chat
     * thread, after the event is cancelled.
     *
     * @return false to leave rendering and delivery to the chat plugin
     */
    default boolean deliver(org.bukkit.entity.Player speaker, String text) {
        return false;
    }
}
