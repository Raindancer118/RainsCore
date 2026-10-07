package de.raindancer.core.platform.command;

import de.raindancer.core.ui.identity.Nicknames;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Who somebody means when they type a name — or {@code @a}, {@code @p}, {@code @r}, {@code @s} and every
 * filter vanilla allows on them — at a command that acts on players.
 *
 * <h2>Why Core's</h2>
 * Every command that moves, heals or hands something to "somebody" asks this, and a copy that only
 * understood names would be the command that silently refuses {@code @a} on exactly the server that
 * expected it to work like vanilla's own {@code /tp}.
 *
 * <h2>Why the server's own parser</h2>
 * {@link Server#selectEntities} is vanilla's selector grammar, filters and permission check included
 * (selectors need the vanilla selector permission). A second grammar here would disagree with
 * {@code /tp} on some filter by next version.
 *
 * <h2>Nicknames</h2>
 * Wherever a name is accepted, so is a nickname from Core's {@link Nicknames} — typed with underscores
 * for spaces — and tab completion offers both. A real name always wins over somebody else's nickname
 * that reads the same, so a nickname can never be used to redirect a command meant for a real player.
 */
public final class PlayerTargets {

    private static final List<String> SELECTORS = List.of("@a", "@p", "@r", "@s");

    /** How many names a suggestion list offers at most; past this the client scrolls a wall. */
    private static final int MAX_SUGGESTIONS = 50;

    /** Set by Core when it enables; null before, after, and in tests that want real names only. */
    private static volatile Nicknames nicknames;

    private PlayerTargets() {
    }

    /** Which nickname directory names are also looked up in. Null for real names only. */
    public static void useNicknames(Nicknames directory) {
        nicknames = directory;
    }

    /** The online player {@code text} names, by real name first and nickname second. */
    public static Optional<Player> online(Server server, String text) {
        if (server == null || text == null || text.isBlank()) {
            return Optional.empty();
        }
        String typed = text.trim();
        Player exact = server.getPlayerExact(typed);
        if (exact != null) {
            return Optional.of(exact);
        }
        return nicknameOwner(typed).map(server::getPlayer);
    }

    /**
     * Anybody {@code text} names, online or not: a real name online, a nickname, then a name the server
     * has seen before. Never a lookup against Mojang — a typo must not stall the server thread.
     */
    public static Optional<OfflinePlayer> find(Server server, String text) {
        if (server == null || text == null || text.isBlank()) {
            return Optional.empty();
        }
        String typed = text.trim();
        Player exact = server.getPlayerExact(typed);
        if (exact != null) {
            return Optional.of(exact);
        }
        Optional<UUID> owner = nicknameOwner(typed);
        if (owner.isPresent()) {
            Player here = server.getPlayer(owner.get());
            return Optional.of(here != null ? here : server.getOfflinePlayer(owner.get()));
        }
        return Optional.ofNullable(server.getOfflinePlayerIfCached(typed));
    }

    /** The UUID {@code text} names, the same way as {@link #find}. */
    public static Optional<UUID> idOf(Server server, String text) {
        return find(server, text).map(OfflinePlayer::getUniqueId);
    }

    /** What to call somebody in a message: their nickname when they have one, else their name. */
    public static String shownName(OfflinePlayer who) {
        if (who == null) {
            return "somebody";
        }
        Nicknames directory = nicknames;
        Optional<String> nickname = directory == null ? Optional.empty() : directory.of(who.getUniqueId());
        if (nickname.isPresent()) {
            return nickname.get();
        }
        String name = who.getName();
        return name == null || name.isBlank() ? who.getUniqueId().toString() : name;
    }

    private static Optional<UUID> nicknameOwner(String typed) {
        Nicknames directory = nicknames;
        return directory == null ? Optional.empty() : directory.ownerOf(typed);
    }

    /**
     * The online players {@code text} means, each once.
     *
     * <p>A selector that does not parse, or one the sender may not use, matches nobody rather than
     * throwing — the caller says "that matched nobody", which is true, and the command does not end in a
     * stack trace.
     */
    public static List<Player> resolve(Server server, CommandSender sender, String text) {
        if (server == null || text == null || text.isBlank()) {
            return List.of();
        }
        String typed = text.trim();
        if (!typed.startsWith("@")) {
            return online(server, typed).map(List::of).orElse(List.of());
        }
        List<Entity> matched;
        try {
            matched = server.selectEntities(sender, typed);
        } catch (IllegalArgumentException malformed) {
            return List.of();
        }
        if (matched == null) {
            return List.of();
        }
        Set<Player> players = new LinkedHashSet<>();
        for (Entity entity : matched) {
            if (entity instanceof Player player) {
                players.add(player);
            }
        }
        return List.copyOf(players);
    }

    /** The selectors, then every online name and nickname, that begin with what has been typed. */
    public static List<String> suggest(Server server, String typed) {
        return suggest(server, typed, who -> true);
    }

    /**
     * The same, of only the online players {@code visible} lets through — so a vanished player is
     * given away neither by name nor by nickname.
     */
    public static List<String> suggest(Server server, String typed, Predicate<Player> visible) {
        String start = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        Set<String> names = new LinkedHashSet<>(SELECTORS);
        Set<UUID> shown = new java.util.HashSet<>();
        if (server != null) {
            for (Player player : server.getOnlinePlayers()) {
                if (visible == null || visible.test(player)) {
                    names.add(player.getName());
                    shown.add(player.getUniqueId());
                }
            }
        }
        Nicknames directory = nicknames;
        if (directory != null) {
            names.addAll(directory.suggest(start, shown::contains));
        }
        return cap(names.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(start))
                .toList());
    }

    /**
     * Names and nicknames of everybody the server knows — online first — for commands that also act
     * on somebody who is not here. No selectors: they only ever match the online.
     *
     * @param visibleOnline which online players may be offered as online; the rest are still offered
     *                      as the offline players everybody else would see them as
     */
    public static List<String> suggestKnown(Server server, String typed, Predicate<Player> visibleOnline) {
        String start = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        Set<String> names = new LinkedHashSet<>();
        if (server != null) {
            for (Player player : server.getOnlinePlayers()) {
                if ((visibleOnline == null || visibleOnline.test(player))
                        && player.getName().toLowerCase(Locale.ROOT).startsWith(start)) {
                    names.add(player.getName());
                }
            }
        }
        Nicknames directory = nicknames;
        if (directory != null) {
            names.addAll(directory.suggest(start, who -> true));
        }
        OfflinePlayer[] everybody = server == null ? null : server.getOfflinePlayers();
        if (everybody != null) {
            for (OfflinePlayer who : everybody) {
                if (names.size() >= MAX_SUGGESTIONS) {
                    break;
                }
                String name = who.getName();
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith(start)) {
                    names.add(name);
                }
            }
        }
        return cap(new ArrayList<>(names));
    }

    private static List<String> cap(List<String> names) {
        return names.size() > MAX_SUGGESTIONS ? List.copyOf(names.subList(0, MAX_SUGGESTIONS)) : names;
    }

    /** Whether {@code text} is a selector rather than a name. */
    public static boolean isSelector(String text) {
        return text != null && text.trim().startsWith("@");
    }
}
