package de.raindancer.core.tablist;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The tablist, as the players see it.
 *
 * <h2>What is here and what is not</h2>
 * Only the sending. Every decision — the grouping, the ordering, what a world is called, what a line
 * says — is {@link TablistModel}, tested without a server. This turns that into packets.
 *
 * <h2>How the ordering actually works</h2>
 * Minecraft does not let a server say "put these players in this order". It sorts the tablist by the
 * scoreboard team each player is in, alphabetically, and that is the only lever there is. So every
 * player is put in a team named by {@link TablistModel#sortKey}, which sorts world-first — and the
 * grouping falls out of the ordering rather than being drawn.
 *
 * <p>Teams are made on the main scoreboard, with names that start with {@code rc-}, and are cleaned
 * up on shutdown. A server using teams for something else keeps working: nothing here touches a team
 * it did not make.
 *
 * <h2>Why it is refreshed on a timer as well as on events</h2>
 * A player's world changes on an event and their ping changes continuously, so an event-only tablist
 * shows a stale latency for ever. The timer is slow — a couple of seconds — because nothing here is
 * urgent and a tablist rebuilt every tick is packets nobody asked for.
 */
public final class Tablists {

    private static final LogChannel log = Log.of("tablist");

    /** Teams we made, so nothing else's are touched. */
    private static final String TEAM_PREFIX = "rc-";

    private final TablistModel model;
    private volatile String serverName;
    private volatile boolean showWorldOnEachLine;
    /** Off leaves the player list exactly as vanilla — nothing here touches it at all. */
    private volatile boolean enabled = true;
    /** Off stops the team-sorting, so the list is in whatever order the server likes. */
    private volatile boolean groupByWorld = true;
    /** What the owner wrote instead of the built-in header, or empty for the built-in one. */
    private volatile String customHeader = "";
    private volatile String customFooter = "";
    /** Extra frames the header and footer cycle through, and how long each lasts. */
    private volatile Animated headerFrames = Animated.of("");
    private volatile Animated footerFrames = Animated.of("");
    /** Counts refreshes, which is what an animation is measured in. */
    private final java.util.concurrent.atomic.AtomicLong tick =
            new java.util.concurrent.atomic.AtomicLong();

    public Tablists(TablistModel model, String serverName) {
        this.model = model;
        this.serverName = serverName;
    }

    public void serverName(String name) {
        this.serverName = name;
    }

    /**
     * Whether each line also says which world that player is in.
     *
     * <p>Off by default: the ordering already groups them, and saying it twice is noise. On for a
     * server that would rather have it beside every name.
     */
    public void showWorldOnEachLine(boolean show) {
        this.showWorldOnEachLine = show;
    }

    /**
     * Whether the player list is ours at all.
     *
     * <p>Switching it off puts everything back: the teams are removed and no header, footer or name
     * is sent again. A setting that only stops updating would leave whatever was last drawn on
     * screen for ever, which is worse than not having the setting.
     */
    public void enabled(boolean on) {
        boolean was = this.enabled;
        this.enabled = on;
        if (was && !on) {
            shutdown();
            restoreNames();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Whether players are sorted so everybody in one world is together. */
    public void groupByWorld(boolean group) {
        boolean was = this.groupByWorld;
        this.groupByWorld = group;
        if (was && !group) {
            // Leave nothing behind: a team that stops being updated still sorts.
            shutdown();
        }
    }

    /** What the owner wrote instead of the built-in header. Empty uses the built-in one. */
    public void header(String miniMessage) {
        this.customHeader = miniMessage == null ? "" : miniMessage;
    }

    public void footer(String miniMessage) {
        this.customFooter = miniMessage == null ? "" : miniMessage;
    }

    /**
     * Extra header lines to cycle through — the rules, an event, a vote that is running.
     *
     * <p>The tablist is one of the few places on a server with room to say something, and a header
     * that never changes is one nobody reads twice.
     *
     * @param everyTicks how many refreshes each frame lasts; the list redraws about twice a second,
     *                   so 4 is roughly two seconds and 1 is a strobe
     */
    public void headerFrames(java.util.List<String> frames, int everyTicks) {
        this.headerFrames = Animated.of(frames).everyTicks(everyTicks);
    }

    public void footerFrames(java.util.List<String> frames, int everyTicks) {
        this.footerFrames = Animated.of(frames).everyTicks(everyTicks);
    }

    public TablistModel model() {
        return model;
    }

    /** Rebuilds everybody's tablist. Called on a timer, and when somebody joins or changes world. */
    public void refresh() {
        if (!enabled) {
            return;
        }
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        List<TablistEntry> entries = new ArrayList<>(online.size());
        for (Player player : online) {
            entries.add(new TablistEntry(player.getUniqueId(), player.getName(),
                    player.getWorld().getName(), player.getPing()));
        }

        long now = tick.getAndIncrement();
        // A frame, if there are any, otherwise whatever the plain header is. Frames win because a
        // server that set them meant to: leaving the still header showing under an animation would
        // be a setting that quietly does nothing.
        String headerNow = headerFrames.isAnimated() || !headerFrames.frameAt(now).isEmpty()
                ? headerFrames.frameAt(now) : customHeader;
        String footerNow = footerFrames.isAnimated() || !footerFrames.frameAt(now).isEmpty()
                ? footerFrames.frameAt(now) : customFooter;
        String customHeader = headerNow;
        String customFooter = footerNow;

        Component header = customHeader.isBlank()
                ? model.header(entries, serverName)
                : model.custom(customHeader, entries, serverName);
        Component footer = customFooter.isBlank()
                ? model.footer(entries)
                : model.custom(customFooter, entries, serverName);

        for (Player player : online) {
            try {
                player.sendPlayerListHeaderAndFooter(header, footer);
            } catch (RuntimeException gone) {
                // Logged out between the list and the send. Normal, not exceptional.
                log.debug("Could not send {} their tablist: {}", player.getName(), gone.toString());
            }
        }

        if (groupByWorld) {
            applyOrder(entries);
        }

        for (int index = 0; index < online.size(); index++) {
            Player player = online.get(index);
            TablistEntry entry = entries.get(index);
            try {
                player.playerListName(showWorldOnEachLine
                        ? model.lineWithWorld(entry)
                        : model.line(entry));
            } catch (RuntimeException gone) {
                log.debug("Could not name {} in the tablist: {}", player.getName(),
                        gone.toString());
            }
        }
    }

    /**
     * Puts each player in the team that sorts them where they belong.
     *
     * <p>Only touched when it has changed: moving a player between teams is a packet to everybody
     * on the server, and doing it every couple of seconds for every player would be a steady trickle
     * of traffic saying nothing.
     */
    private void applyOrder(List<TablistEntry> entries) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (TablistEntry entry : entries) {
            String wanted = TEAM_PREFIX + model.sortKey(entry);
            Player player = Bukkit.getPlayer(entry.player());
            if (player == null) {
                continue;
            }
            try {
                Team current = board.getEntryTeam(player.getName());
                if (current != null && current.getName().equals(wanted)) {
                    continue;
                }
                if (current != null && current.getName().startsWith(TEAM_PREFIX)) {
                    current.removeEntry(player.getName());
                    // Unregistered once nobody is in it. These live on the MAIN scoreboard, which
                    // Minecraft saves to scoreboard.dat — an empty team left behind is not merely
                    // untidy, it is written to disk for ever, and a server whose players change
                    // world often would accumulate thousands.
                    if (current.getEntries().isEmpty()) {
                        current.unregister();
                    }
                } else if (current != null) {
                    // Somebody else's team. Leaving it alone is the only safe thing to do: taking a
                    // player out of another plugin's team to sort a list would break whatever that
                    // team was for.
                    continue;
                }
                Team team = board.getTeam(wanted);
                if (team == null) {
                    team = board.registerNewTeam(wanted);
                }
                team.addEntry(player.getName());
            } catch (RuntimeException failure) {
                log.debug("Could not sort {} in the tablist: {}", entry.name(),
                        failure.toString());
            }
        }
    }

    /** Puts every player's name back to plain, for when the custom list is switched off. */
    private void restoreNames() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                player.playerListName(null);
                player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            } catch (RuntimeException gone) {
                log.debug("Could not restore {}'s tablist entry: {}", player.getName(),
                        gone.toString());
            }
        }
    }

    /** Removes the teams this class made. Called from {@code onDisable}. */
    public void shutdown() {
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            for (Team team : new ArrayList<>(board.getTeams())) {
                if (team.getName().startsWith(TEAM_PREFIX)) {
                    team.unregister();
                }
            }
        } catch (RuntimeException failure) {
            log.debug("Could not tidy up the tablist teams: {}", failure.toString());
        }
    }

    /** Forgets a player who has left, so their team does not linger. */
    public void forget(Player player) {
        if (player == null) {
            return;
        }
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            Team team = board.getEntryTeam(player.getName());
            if (team != null && team.getName().startsWith(TEAM_PREFIX)) {
                team.removeEntry(player.getName());
                if (team.getEntries().isEmpty()) {
                    team.unregister();
                }
            }
        } catch (RuntimeException failure) {
            log.debug("Could not take {} out of their tablist team: {}", player.getName(),
                    failure.toString());
        }
    }

    /** Whether two entries are the same player in the same place — for a cheap change check. */
    static boolean sameSpot(TablistEntry one, TablistEntry other) {
        return one != null && other != null
                && Objects.equals(one.player(), other.player())
                && Objects.equals(one.world(), other.world());
    }
}
