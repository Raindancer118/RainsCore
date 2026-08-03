package de.raindancer.core.tablist;

import de.raindancer.core.identity.Identities;
import de.raindancer.core.identity.Symbols;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the tablist says: who is on, and which world they are in.
 *
 * <h2>Why this is separate from the thing that sends it</h2>
 * The same reason as everywhere else here — sending needs a server, deciding does not. Grouping,
 * ordering, what a world is called, what a player's line says and what the header counts are all
 * decisions somebody will want to change, and none of them should need a server to get right.
 *
 * <h2>Why the sort key is a scoreboard team name</h2>
 * The order of the tablist is not ours to set directly. Minecraft sorts it by the scoreboard team a
 * player is in, alphabetically, and that is the only lever there is. So {@link #sortKey} produces a
 * name that sorts the way the groups do — world first, then player — and {@code Tablists} puts each
 * player in a team called that. It is why the key has a length limit: the server refuses a team name
 * past it, and a refused team is a player who lands in the wrong place with no error.
 */
public final class TablistModel {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * How long a sort key may be.
     *
     * <p>A scoreboard team name is capped, and a name past the cap is refused outright — which shows
     * up as one player sorted wrongly and nothing in the log.
     */
    public static final int MAX_SORT_KEY = 16;

    /** The vanilla worlds, in the order they belong in. Anything else follows, alphabetically. */
    private static final List<String> VANILLA_ORDER = List.of("", "_nether", "_the_end");

    private final Identities identities;

    public TablistModel(Identities identities) {
        this.identities = identities;
    }

    // ------------------------------------------------------------------------ worlds

    /**
     * A world's name, as a person would say it.
     *
     * <p>{@code world_nether} is a folder name; "Nether" is what somebody looking at a tablist wants
     * to read. A world nobody has an opinion about gets its own name tidied rather than left raw,
     * which is right often enough to be worth doing and never worse than showing the folder.
     */
    public static String worldLabel(String world) {
        if (world == null || world.isBlank()) {
            return "Somewhere";
        }
        String name = world.trim();
        // The default names first, because they are the ones every server has.
        if (name.equals("world")) {
            return "Overworld";
        }
        if (name.equals("world_nether")) {
            return "Nether";
        }
        if (name.equals("world_the_end")) {
            return "The End";
        }
        // A custom set — farmworld, farmworld_nether, farmworld_the_end — reads as its own place
        // followed by which half of it, which is what somebody with a farm world actually wants.
        if (name.endsWith("_nether")) {
            return readable(name.substring(0, name.length() - "_nether".length())) + " Nether";
        }
        if (name.endsWith("_the_end")) {
            return readable(name.substring(0, name.length() - "_the_end".length())) + " End";
        }
        return readable(name);
    }

    /** The character in front of a world's heading, so the list scans without being read. */
    public static String worldSymbol(String world) {
        String name = world == null ? "" : world.trim();
        if (name.endsWith("_nether")) {
            return Symbols.WARNING;
        }
        if (name.endsWith("_the_end")) {
            return Symbols.STAR;
        }
        return Symbols.HOME;
    }

    private static String readable(String raw) {
        String words = raw.replace('_', ' ').replace('-', ' ').trim();
        return words.isEmpty() ? words
                : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    // ------------------------------------------------------------------------ grouping

    /**
     * Everybody on, gathered under the world they are in.
     *
     * <p>Overworld, Nether, End, then everything else alphabetically — the order somebody expects
     * rather than the order the server happens to list its worlds in. Empty worlds do not appear,
     * because a heading with nobody under it is a heading somebody has to read past.
     */
    public List<TablistGroup> groups(List<TablistEntry> online) {
        if (online == null || online.isEmpty()) {
            return List.of();
        }
        Map<String, List<TablistEntry>> byWorld = new LinkedHashMap<>();
        for (TablistEntry entry : online) {
            byWorld.computeIfAbsent(entry.world(), key -> new ArrayList<>()).add(entry);
        }

        List<String> worlds = new ArrayList<>(byWorld.keySet());
        worlds.sort(Comparator.comparingInt(TablistModel::worldRank)
                .thenComparing(TablistModel::worldLabel, String.CASE_INSENSITIVE_ORDER));

        List<TablistGroup> groups = new ArrayList<>(worlds.size());
        for (String world : worlds) {
            List<TablistEntry> entries = new ArrayList<>(byWorld.get(world));
            entries.sort(Comparator.comparing(TablistEntry::name, String.CASE_INSENSITIVE_ORDER));
            groups.add(new TablistGroup(world, worldLabel(world), worldSymbol(world),
                    List.copyOf(entries)));
        }
        return List.copyOf(groups);
    }

    /** Lower sorts first: the three vanilla worlds, then everything else. */
    private static int worldRank(String world) {
        String name = world == null ? "" : world;
        if (name.equals("world")) {
            return 0;
        }
        if (name.equals("world_nether")) {
            return 1;
        }
        if (name.equals("world_the_end")) {
            return 2;
        }
        return 3;
    }

    // ------------------------------------------------------------------------ lines

    /**
     * One player's line: their prefix, their name in their colour, their suffix.
     *
     * <p>Straight through {@link Identities}, so a rank set once shows in chat, above their head and
     * here — which is the point of having identities at all.
     */
    public Component line(TablistEntry entry) {
        return identities.chatName(entry.player(), entry.name());
    }

    /** The same, with the world after it — for a server that would rather not group. */
    public Component lineWithWorld(TablistEntry entry) {
        return line(entry)
                .append(MINI.deserialize("<dark_gray> · <gray>" + worldLabel(entry.world())));
    }

    /** A group's heading. */
    public Component heading(TablistGroup group) {
        return MINI.deserialize("<dark_gray>" + group.symbol() + " <gray><b>" + group.label()
                + "</b> <dark_gray>(" + group.size() + ")");
    }

    // ------------------------------------------------------------------------ header and footer

    /** What sits above the list: the server, and how many are on. */
    public Component header(List<TablistEntry> online, String serverName) {
        String name = serverName == null || serverName.isBlank() ? "This server" : serverName.trim();
        int count = online == null ? 0 : online.size();
        return MINI.deserialize("\n<gradient:#C9A0FF:#7C5CBF><b>" + escape(name)
                + "</b></gradient>\n<gray>" + count
                + (count == 1 ? " player online" : " players online") + "\n");
    }

    /**
     * What sits below it: how many are in each world.
     *
     * <p>The per-world count is the whole point — it answers "is anybody in the nether" without
     * scrolling, which a grouped list only answers by being read.
     */
    public Component footer(List<TablistEntry> online) {
        List<TablistGroup> groups = groups(online);
        if (groups.isEmpty()) {
            return MINI.deserialize("\n<dark_gray>nobody is on\n");
        }
        StringBuilder built = new StringBuilder("\n");
        for (int index = 0; index < groups.size(); index++) {
            TablistGroup group = groups.get(index);
            if (index > 0) {
                built.append("<dark_gray>  ·  ");
            }
            built.append("<gray>").append(group.symbol()).append(' ')
                    .append(escape(group.label()))
                    .append(" <white>").append(group.size());
        }
        return MINI.deserialize(built.append('\n').toString());
    }

    // ------------------------------------------------------------------------ ordering

    /**
     * The scoreboard team name that puts this player where they belong.
     *
     * <p>Two characters of world rank, then the name, then enough of their id to be unique — because
     * two players called the same thing in the same world must not share a team, and sharing one
     * means the second is silently not added.
     */
    public String sortKey(TablistEntry entry) {
        String rank = String.valueOf((char) ('a' + Math.min(9, worldRank(entry.world()))));
        String name = entry.name().toLowerCase(Locale.ROOT);
        String unique = Integer.toHexString(entry.player().hashCode());
        int room = MAX_SORT_KEY - rank.length() - 4;
        String trimmed = name.length() > room ? name.substring(0, Math.max(0, room)) : name;
        String key = rank + trimmed + unique;
        return key.length() > MAX_SORT_KEY ? key.substring(0, MAX_SORT_KEY) : key;
    }

    private static String escape(String raw) {
        return MINI.escapeTags(raw);
    }
}
