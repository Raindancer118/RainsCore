package de.raindancer.core.platform.command;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
 */
public final class PlayerTargets {

    private static final List<String> SELECTORS = List.of("@a", "@p", "@r", "@s");

    private PlayerTargets() {
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
            Player exact = server.getPlayerExact(typed);
            return exact == null ? List.of() : List.of(exact);
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

    /** The selectors, then every online name, that begin with what has been typed. */
    public static List<String> suggest(Server server, String typed) {
        String start = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>(SELECTORS);
        if (server != null) {
            server.getOnlinePlayers().stream().map(Player::getName).forEach(names::add);
        }
        return names.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(start))
                .toList();
    }

    /** Whether {@code text} is a selector rather than a name. */
    public static boolean isSelector(String text) {
        return text != null && text.trim().startsWith("@");
    }
}
