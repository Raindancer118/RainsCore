package de.raindancer.core.ui.chat;

import de.raindancer.core.platform.util.PluginCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

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
    private static final Set<Object> routers = ConcurrentHashMap.newKeySet();
    private static final List<BiConsumer<UUID, String>> watchers = new CopyOnWriteArrayList<>();

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
            chosen(player, ALL);
            return true;
        }
        Optional<ChatChannel> channel = byId(id);
        if (channel.isEmpty()) {
            return false;
        }
        selected.put(player, channel.get().id());
        chosen(player, channel.get().id());
        return true;
    }

    /**
     * Told the channel id (or {@link #ALL}) each time somebody chooses one, on whoever's thread chose
     * it — so a chat plugin with a mode of its own (a private chat) can step aside and let the latest
     * choice win, whichever plugin's command made it.
     */
    public static void watch(BiConsumer<UUID, String> watcher) {
        if (watcher != null) {
            watchers.add(watcher);
        }
    }

    public static void unwatch(BiConsumer<UUID, String> watcher) {
        watchers.remove(watcher);
    }

    private static void chosen(UUID player, String id) {
        for (var watcher : watchers) {
            watcher.accept(player, id);
        }
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

    /**
     * Drops whatever {@code loader}'s code registered here — for a plugin disabled without taking its
     * own back. See {@link PluginCode}.
     *
     * @return how many were dropped
     */
    public static int forgetFrom(ClassLoader loader) {
        int before = channels.size() + routers.size() + watchers.size();
        channels.removeIf(channel -> PluginCode.isFrom(channel, loader));
        routers.removeIf(router -> PluginCode.isFrom(router, loader));
        watchers.removeIf(watcher -> PluginCode.isFrom(watcher, loader));
        return before - channels.size() - routers.size() - watchers.size();
    }

    public static void forget(UUID player) {
        selected.remove(player);
    }

    /**
     * Says that {@code router} — a chat plugin — routes channel lines from here on. A module that owns
     * a channel and can deliver it without a chat plugin (staff chat) stands down while this is true,
     * so a line is said once and not twice.
     */
    public static void claimRouting(Object router) {
        if (router != null) {
            routers.add(router);
        }
    }

    public static void releaseRouting(Object router) {
        if (router != null) {
            routers.remove(router);
        }
    }

    public static boolean isRouted() {
        return !routers.isEmpty();
    }
}
