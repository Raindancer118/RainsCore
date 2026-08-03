package de.raindancer.core.content.achievement;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.YamlStore;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Custom achievements: what a player has done, and what they are working towards.
 *
 * <h2>The one thing that has to be right</h2>
 * An achievement is earned <em>once</em>. A player who claims their second plot has not earned
 * "your first claim" again, and a listener that fires twice must not announce it twice — which is
 * the bug every hand-rolled version of this has, because the check and the write are two steps and
 * something always slips between them. Here they are one atomic step, so eight threads awarding at
 * the same moment award it once and announce it once.
 *
 * <h2>How a plugin uses it</h2>
 * <pre>
 * // At startup, shipping a default the owner may then edit:
 * achievements.defineIfAbsent(Achievement.builder("claims", "first-claim")
 *         .title("&lt;gold&gt;Landowner")
 *         .description("Claim your first plot")
 *         .icon(Material.GRASS_BLOCK)
 *         .points(10)
 *         .build());
 *
 * // When it happens — safe to call every time, it only lands once:
 * achievements.award(player, "claims:first-claim");
 *
 * // Or, for one they work towards:
 * achievements.progress(player, "ghasts:every-stop", 1);
 * </pre>
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. Awards are held in a {@link ConcurrentHashMap} per player and the award
 * itself is a {@code putIfAbsent}, which is what makes "only once" true rather than hoped for.
 */
public final class Achievements {

    private static final LogChannel log = Log.of("achievements");

    private final Path file;
    private final YamlStore store;
    private final LongSupplier clock;
    private final Map<String, Achievement> defined = new ConcurrentHashMap<>();
    /** Who has earned what, and when. */
    private final Map<UUID, Map<String, Instant>> earned = new ConcurrentHashMap<>();
    /** How far along somebody is with one they are working towards. */
    private final Map<UUID, Map<String, Integer>> progress = new ConcurrentHashMap<>();
    private final List<BiConsumer<UUID, Achievement>> listeners = new CopyOnWriteArrayList<>();
    private final Database database;
    /** Set when a definition changed and the file needs rewriting. */
    private final AtomicBoolean dirty = new AtomicBoolean();
    /** Which players' rows need writing — the database half. */
    private final Set<UUID> changedPlayers = ConcurrentHashMap.newKeySet();

    /**
     * @param clock milliseconds; injected so "when was this earned" can be tested
     * @param file     where the achievement <em>definitions</em> live: what they are called, what they
     *                 are worth, what they look like. Written by whoever runs the server and read at
     *                 startup, so a file with comments in it is the right home for them —
     *                 {@link #defineIfAbsent} exists precisely so a plugin's defaults do not undo the
     *                 owner's edits
     * @param database where <em>who earned what</em> lives. Written while players are on, never
     *                 edited by hand, and the half that a kill mid-save would otherwise cost somebody
     */
    public Achievements(Path file, Database database, LongSupplier clock) {
        this.database = database;
        this.file = file;
        this.store = new YamlStore(file);
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------- defining

    public void define(Achievement achievement) {
        if (achievement != null) {
            defined.put(achievement.key(), achievement);
            dirty.set(true);
        }
    }

    /**
     * Defines it only if nobody has — how a plugin ships a default without undoing the owner's edits
     * on every restart.
     */
    public boolean defineIfAbsent(Achievement achievement) {
        if (achievement == null) {
            return false;
        }
        boolean added = defined.putIfAbsent(achievement.key(), achievement) == null;
        if (added) {
            dirty.set(true);
        }
        return added;
    }

    public Optional<Achievement> byKey(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(defined.get(normalise(key)));
    }

    public List<Achievement> all() {
        return List.copyOf(defined.values());
    }

    public List<Achievement> ofPlugin(String plugin) {
        if (plugin == null) {
            return List.of();
        }
        String wanted = plugin.trim().toLowerCase(Locale.ROOT);
        return defined.values().stream().filter(each -> each.plugin().equals(wanted)).toList();
    }

    /**
     * What this player should see in a list: everything, minus the hidden ones they have not earned.
     *
     * <p>Hidden ones appear the moment they are earned, which is the point of them — the surprise
     * is in the finding, not in never being told it existed.
     */
    public List<Achievement> visibleTo(UUID player) {
        return defined.values().stream()
                .filter(each -> !each.hidden() || hasEarned(player, each.key()))
                .toList();
    }

    // ---------------------------------------------------------------------------- earning

    /**
     * Records that a player earned this. Safe to call every time the thing happens.
     *
     * @return whether this was the first time; false means they already had it
     */
    public boolean award(UUID player, String key) {
        if (player == null || key == null) {
            return false;
        }
        Achievement achievement = defined.get(normalise(key));
        if (achievement == null) {
            // Deliberately not invented: an achievement nobody defined has no title, no icon and no
            // points, so awarding it would put a blank line in somebody's list for ever.
            log.warn("{} was awarded '{}', which nothing defines.", player, key);
            return false;
        }
        // One atomic step. Checking and then writing is where "announced twice" comes from.
        Instant already = earnedBy(player).putIfAbsent(achievement.key(),
                Instant.ofEpochMilli(clock.getAsLong()));
        if (already != null) {
            return false;
        }
        changedPlayers.add(player);
        announce(player, achievement);
        return true;
    }

    /** Takes one back, for a mistake. */
    public boolean revoke(UUID player, String key) {
        if (player == null || key == null || earnedBy(player).remove(normalise(key)) == null) {
            return false;
        }
        changedPlayers.add(player);
        return true;
    }

    public boolean hasEarned(UUID player, String key) {
        return player != null && key != null && earnedBy(player).containsKey(normalise(key));
    }

    /** When they earned it, if they did. */
    public Optional<Instant> earnedAt(UUID player, String key) {
        if (player == null || key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(earnedBy(player).get(normalise(key)));
    }

    /** Everything this player has earned. */
    public Map<String, Instant> earnedBy(UUID player) {
        if (player == null) {
            return Map.of();
        }
        return earned.computeIfAbsent(player, key -> new ConcurrentHashMap<>());
    }

    /** What this player's achievements are worth, for a leaderboard. */
    public int pointsOf(UUID player) {
        return earnedBy(player).keySet().stream()
                .map(defined::get)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Achievement::points)
                .sum();
    }

    // ---------------------------------------------------------------------------- progress

    /**
     * Adds to how far along a player is, and awards it when they reach the goal.
     *
     * <p>An achievement with no goal is completed by any progress at all, so a plugin does not have
     * to know which kind it is dealing with.
     */
    public void progress(UUID player, String key, int by) {
        if (player == null || key == null) {
            return;
        }
        String normalised = normalise(key);
        int updated = progressOf(player).merge(normalised, Math.max(0, by), Integer::sum);
        changedPlayers.add(player);
        checkGoal(player, normalised, updated);
    }

    /** Sets it outright — for a count that is recomputed rather than added to. */
    public void setProgress(UUID player, String key, int to) {
        if (player == null || key == null) {
            return;
        }
        String normalised = normalise(key);
        progressOf(player).put(normalised, Math.max(0, to));
        changedPlayers.add(player);
        checkGoal(player, normalised, Math.max(0, to));
    }

    /** How far along they are. */
    public int progressOf(UUID player, String key) {
        if (player == null || key == null) {
            return 0;
        }
        return progressOf(player).getOrDefault(normalise(key), 0);
    }

    private Map<String, Integer> progressOf(UUID player) {
        return progress.computeIfAbsent(player, key -> new ConcurrentHashMap<>());
    }

    private void checkGoal(UUID player, String key, int now) {
        Achievement achievement = defined.get(key);
        if (achievement == null) {
            return;
        }
        int goal = achievement.goal().orElse(1);
        if (now >= goal) {
            // award() is the one that guarantees once, so going past the goal is harmless.
            award(player, key);
        }
    }

    // ---------------------------------------------------------------------------- announcing

    /**
     * Called whenever somebody earns one — for whatever announces it.
     *
     * <p>Deliberately not announced from here: what a server does about an achievement is its own
     * business, and a title, a sound, a broadcast and a firework are all reasonable and none of them
     * belongs in a store.
     */
    public void onEarned(BiConsumer<UUID, Achievement> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void announce(UUID player, Achievement achievement) {
        for (BiConsumer<UUID, Achievement> listener : listeners) {
            try {
                listener.accept(player, achievement);
            } catch (RuntimeException broken) {
                // One plugin's celebration must not stop another's, nor undo the award itself.
                log.error(broken, "An achievement listener threw for {}.", achievement.key());
            }
        }
    }

    private static String normalise(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------------------- the file

    /** Whether either half is waiting to be written. */
    public boolean isDirty() {
        return dirty.get() || !changedPlayers.isEmpty();
    }

    public void load() {
        defined.clear();
        earned.clear();
        progress.clear();
        if (!store.exists()) {
            dirty.set(false);
            return;
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            log.error("Could not read {} ({}); nobody's achievements are known this session.",
                    file, String.join("; ", store.problems()));
            return;
        }
        readDefinitions(yaml.getConfigurationSection("achievements"));
        dirty.set(false);
        loadPlayers();
    }

    /**
     * Reads who has earned what, out of the database.
     *
     * <p>Separate from the definitions on purpose: what an achievement <em>is</em> comes from a file
     * somebody wrote, and who <em>earned</em> it is something the server recorded. The two have
     * different owners and different failure modes, so they have different homes.
     */
    private void loadPlayers() {
        earned.clear();
        progress.clear();
        changedPlayers.clear();
        if (!database.isUsable()) {
            log.error("The achievement tables are not available; nobody's achievements are known "
                    + "this session.");
            return;
        }
        boolean read = database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT player, achievement, earned_at FROM achievement_earned");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID player = playerOf(rows.getString("player"));
                    if (player != null) {
                        earnedBy(player).put(rows.getString("achievement"),
                                Instant.ofEpochMilli(rows.getLong("earned_at")));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT player, achievement, sofar FROM achievement_progress");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID player = playerOf(rows.getString("player"));
                    if (player != null) {
                        progressOf(player).put(rows.getString("achievement"), rows.getInt("sofar"));
                    }
                }
            }
            return true;
        }).orElse(false);
        if (!read) {
            log.error("The earned achievements could not be read; nobody's are known this session.");
        }
    }

    /** A player id, or null when the row holds something that is not one. */
    private static UUID playerOf(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException notAUuid) {
            // One unreadable row is one player's achievement, not everybody's.
            log.warn("An achievement row for '{}' was skipped: that is not a player id.", value);
            return null;
        }
    }

    private void readDefinitions(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            int colon = key.indexOf(':');
            if (entry == null || colon <= 0) {
                continue;
            }
            try {
                Achievement.Builder built = Achievement.builder(key.substring(0, colon),
                                key.substring(colon + 1))
                        .title(entry.getString("title"))
                        .description(entry.getString("description"))
                        .points(entry.getInt("points"))
                        .hidden(entry.getBoolean("hidden"));
                String icon = entry.getString("icon");
                if (icon != null && !icon.isBlank()) {
                    built.icon(Material.matchMaterial(icon));
                }
                if (entry.contains("goal")) {
                    built.goal(entry.getInt("goal"));
                }
                Achievement achievement = built.build();
                defined.put(achievement.key(), achievement);
            } catch (RuntimeException broken) {
                log.warn("{}: achievement '{}' was skipped ({})",
                        file.getFileName(), key, broken.getMessage());
            }
        }
    }


    /** Writes, if anything changed. Via a temporary file, so a kill mid-write cannot truncate it. */
    /**
     * Writes both halves: the definitions to their file, and who earned what to the database.
     *
     * <p>Two halves with two conditions, so a server where nobody has changed a definition does not
     * rewrite the definitions file every two minutes to record that somebody earned something.
     *
     * <p>Must be called off the server's threads.
     */
    public void flush() {
        flushDefinitions();
        flushPlayers();
    }

    private void flushDefinitions() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        List<Achievement> snapshot = List.copyOf(defined.values());
        boolean written = store.write(yaml -> {
            for (Achievement achievement : snapshot) {
                String path = "achievements." + achievement.key() + ".";
                yaml.set(path + "title", achievement.title());
                if (!achievement.description().isEmpty()) {
                    yaml.set(path + "description", achievement.description());
                }
                yaml.set(path + "icon", achievement.icon().name());
                if (achievement.points() > 0) {
                    yaml.set(path + "points", achievement.points());
                }
                achievement.goal().ifPresent(goal -> yaml.set(path + "goal", goal));
                if (achievement.hidden()) {
                    yaml.set(path + "hidden", true);
                }
            }
        });
        if (!written) {
            dirty.set(true);
        }
    }

    /**
     * Writes the players whose achievements changed.
     *
     * <p>Everything a player has is rewritten rather than the one thing that changed: a revoke has to
     * take a row away, and working out which rows went is more code than replacing the handful a
     * player has. All inside one transaction, so nobody is ever seen mid-rewrite.
     */
    private void flushPlayers() {
        if (changedPlayers.isEmpty() || !database.isUsable()) {
            return;
        }
        Set<UUID> writing = Set.copyOf(changedPlayers);
        boolean written = database.write(connection -> {
            try (PreparedStatement clearEarned = connection.prepareStatement(
                         "DELETE FROM achievement_earned WHERE player = ?");
                 PreparedStatement clearProgress = connection.prepareStatement(
                         "DELETE FROM achievement_progress WHERE player = ?");
                 PreparedStatement addEarned = connection.prepareStatement(
                         "INSERT INTO achievement_earned (player, achievement, earned_at) "
                                 + "VALUES (?, ?, ?)");
                 PreparedStatement addProgress = connection.prepareStatement(
                         "INSERT INTO achievement_progress (player, achievement, sofar) "
                                 + "VALUES (?, ?, ?)")) {
                for (UUID player : writing) {
                    clearEarned.setString(1, player.toString());
                    clearEarned.executeUpdate();
                    clearProgress.setString(1, player.toString());
                    clearProgress.executeUpdate();
                    for (Map.Entry<String, Instant> got : earnedBy(player).entrySet()) {
                        addEarned.setString(1, player.toString());
                        addEarned.setString(2, got.getKey());
                        addEarned.setLong(3, got.getValue().toEpochMilli());
                        addEarned.executeUpdate();
                    }
                    for (Map.Entry<String, Integer> towards : progressOf(player).entrySet()) {
                        addProgress.setString(1, player.toString());
                        addProgress.setString(2, towards.getKey());
                        addProgress.setInt(3, towards.getValue());
                        addProgress.executeUpdate();
                    }
                }
            }
        });
        if (written) {
            changedPlayers.removeAll(writing);
        }
    }

    /**
     * A key, safe to use as a YAML path.
     *
     * <p>{@code claims:first-claim} is fine as a section name but a dot in one would nest, and a
     * plugin id is not guaranteed to be free of them. The colon is swapped for a character that
     * cannot appear in either half.
     */
    private static String escape(String key) {
        return key.replace('.', '·');
    }

    private static String unescape(String key) {
        return key.replace('·', '.');
    }
}
