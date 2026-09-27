package de.raindancer.core.ui.chat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every chat channel modules offer, and which one each player is talking in.
 *
 * <p>Static and server-wide for the same reason as {@code ProfileExtensions}: modules register from
 * their own {@code enable()} and unregister on {@code disable()}, and the chat plugin — whichever one
 * is installed — reads from here. A selection is kept in memory; after a restart everybody talks to
 * everybody again, which is the safe way round.
 */
public final class ChatChannels {

    /** Public chat — not a registered channel, the absence of one. */
    public static final String ALL = "all";

    /** Where a line goes, when it does not go to everybody. */
    public record Route(ChatChannel channel, Set<UUID> audience, String tag) {
    }

    private static final List<ChatChannel> channels = new CopyOnWriteArrayList<>();
    private static final Map<UUID, String> selected = new ConcurrentHashMap<>();

    private ChatChannels() {
    }

    public static void register(ChatChannel channel) {
        if (channel != null) {
            channels.add(channel);
        }
    }

    public static void unregister(ChatChannel channel) {
        channels.remove(channel);
    }

    public static List<ChatChannel> all() {
        return List.copyOf(channels);
    }

    public static Optional<ChatChannel> byId(String id) {
        return channels.stream().filter(channel -> channel.id().equalsIgnoreCase(id)).findFirst();
    }

    /** The channels {@code player} could talk in right now. */
    public static List<ChatChannel> availableTo(UUID player) {
        return channels.stream().filter(channel -> channel.audienceFor(player).isPresent()).toList();
    }

    /** Switches {@code player} to a channel, or back to {@link #ALL}. @return false for an unknown id */
    public static boolean select(UUID player, String id) {
        if (ALL.equalsIgnoreCase(id)) {
            selected.remove(player);
            return true;
        }
        Optional<ChatChannel> channel = byId(id);
        if (channel.isEmpty()) {
            return false;
        }
        selected.put(player, channel.get().id());
        return true;
    }

    public static String selected(UUID player) {
        return selected.getOrDefault(player, ALL);
    }

    /**
     * Where {@code speaker}'s next line goes: their channel's audience, or empty for everybody —
     * also when that channel is gone or they have no part in it right now.
     */
    public static Optional<Route> route(UUID speaker) {
        String id = selected.get(speaker);
        if (id == null) {
            return Optional.empty();
        }
        return byId(id).flatMap(channel -> channel.audienceFor(speaker)
                .map(audience -> new Route(channel, Set.copyOf(audience), channel.tagFor(speaker))));
    }

    public static void forget(UUID player) {
        selected.remove(player);
    }
}
