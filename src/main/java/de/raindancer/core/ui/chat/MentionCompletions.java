package de.raindancer.core.ui.chat;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.identity.Nicknames;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * What a player's chat box offers after an {@code @}: everybody they could mention, by name and nickname.
 *
 * <h2>Why custom chat completions</h2>
 * Since 1.13 the client completes ordinary chat from what the server has <em>told it</em> — the player list
 * and the custom chat completions — and never asks again while typing. A tab-complete listener for chat
 * therefore never fires on a real client, however right it looks. So the list is pushed instead, and kept
 * current: who joined, who left, who vanished, who renamed themselves.
 *
 * <p>Off until a plugin that actually renders mentions switches it on — {@code @Name} in the chat box of a
 * server that does nothing with it would be a suggestion that lies.
 */
public final class MentionCompletions {

    /** How often the lists are compared with what each player was last sent. */
    private static final long REFRESH_TICKS = 40L;
    /** How long the offline names are kept before the server's player files are walked again. */
    private static final Duration OFFLINE_REREAD = Duration.ofMinutes(5);
    private static final int MOST_OFFLINE = 500;

    /** The online players, by id and name. */
    public record Known(Map<UUID, String> online) {
    }

    public record Change(List<String> added, List<String> removed) {

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }
    }

    private final Plugin plugin;
    private final Server server;
    private final Vanish vanish;
    private final Nicknames nicknames;
    private final Map<UUID, Set<String>> sent = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile List<String> offlineNames = List.of();
    private volatile long offlineReadAt;
    private ScheduledTask timer;

    public MentionCompletions(Plugin plugin, Server server, Vanish vanish, Nicknames nicknames) {
        this.plugin = plugin;
        this.server = server;
        this.vanish = vanish;
        this.nicknames = nicknames;
    }

    /** Switches the completions on or off for everybody. A chat plugin calls this with its mentions setting. */
    public synchronized void enable(boolean on) {
        if (on == enabled) {
            return;
        }
        enabled = on;
        if (on) {
            timer = Scheduling.globalTimer(plugin, 1L, REFRESH_TICKS, task -> refresh());
        } else {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            for (Player player : server.getOnlinePlayers()) {
                Set<String> had = sent.remove(player.getUniqueId());
                if (had != null && !had.isEmpty()) {
                    Scheduling.onOwner(plugin, player, () -> player.removeCustomChatCompletions(had));
                }
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Compares every player's list with what they were last sent, and sends only what changed. */
    public void refresh() {
        if (!enabled) {
            return;
        }
        Map<UUID, String> online = new HashMap<>();
        Collection<? extends Player> players = server.getOnlinePlayers();
        for (Player player : players) {
            online.put(player.getUniqueId(), player.getName());
        }
        Known known = new Known(online);
        Map<UUID, String> nicks = new HashMap<>();
        if (nicknames != null) {
            for (UUID id : online.keySet()) {
                nicknames.of(id).ifPresent(nick -> nicks.put(id, Nicknames.suggestion(nick)));
            }
            for (String nick : nicknames.suggest("", who -> !online.containsKey(who))) {
                nicks.put(UUID.nameUUIDFromBytes(("offline:" + nick).getBytes()), nick);
            }
        }
        List<String> offline = offlineNames();
        for (Player viewer : players) {
            UUID id = viewer.getUniqueId();
            Set<String> wanted = new LinkedHashSet<>(completionsFor(id, known,
                    target -> vanish == null || vanish.canSee(id, target), nicks, offline));
            Change change = difference(sent.getOrDefault(id, Set.of()), wanted);
            if (change.isEmpty()) {
                continue;
            }
            sent.put(id, wanted);
            Scheduling.onOwner(plugin, viewer, () -> {
                if (!change.removed().isEmpty()) {
                    viewer.removeCustomChatCompletions(change.removed());
                }
                if (!change.added().isEmpty()) {
                    viewer.addCustomChatCompletions(change.added());
                }
            });
        }
    }

    private List<String> offlineNames() {
        long now = System.currentTimeMillis();
        if (now - offlineReadAt < OFFLINE_REREAD.toMillis()) {
            return offlineNames;
        }
        offlineReadAt = now;
        // Every known name in alphabetical order, cut at a fixed count — never by when somebody was last
        // seen, which a hidden player who just joined would change and so give themselves away.
        java.util.TreeSet<String> sorted = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        OfflinePlayer[] everybody = server.getOfflinePlayers();
        if (everybody != null) {
            for (OfflinePlayer who : everybody) {
                if (who.getName() != null) {
                    sorted.add(who.getName());
                }
            }
        }
        List<String> names = sorted.stream().limit(MOST_OFFLINE).toList();
        offlineNames = List.copyOf(names);
        return offlineNames;
    }

    /** A player who left: their list is gone with them, and is sent whole on their next join. */
    public void forget(UUID player) {
        sent.remove(player);
    }

    /**
     * What {@code viewer}'s chat box should offer: every online player they can see by name and nickname,
     * then the offline by nickname and name. Never themselves, never somebody hidden from them.
     *
     * @param nicknames nicknames in their typed form, by owner; owners not online stand for offline players
     */
    public static List<String> completionsFor(UUID viewer, Known known, Predicate<UUID> canSee,
                                              Map<UUID, String> nicknames, List<String> offline) {
        Set<String> offered = new LinkedHashSet<>();
        Set<UUID> visible = new java.util.HashSet<>();
        known.online().forEach((id, name) -> {
            if (!id.equals(viewer) && canSee.test(id)) {
                visible.add(id);
                offered.add("@" + name);
                String nick = nicknames.get(id);
                if (nick != null) {
                    offered.add("@" + nick);
                }
            }
        });
        // Everybody else exactly as the offline are offered — a hidden player missing from this part
        // would tell anybody watching their suggestions that they are online.
        nicknames.forEach((id, nick) -> {
            if (!id.equals(viewer) && !visible.contains(id)) {
                offered.add("@" + nick);
            }
        });
        String own = known.online().get(viewer);
        for (String name : offline) {
            if (own == null || !own.equalsIgnoreCase(name)) {
                offered.add("@" + name);
            }
        }
        return new ArrayList<>(offered);
    }

    public static Change difference(Set<String> had, Set<String> wanted) {
        List<String> added = wanted.stream().filter(name -> !had.contains(name)).toList();
        List<String> removed = had.stream().filter(name -> !wanted.contains(name)).toList();
        return new Change(added, removed);
    }
}
