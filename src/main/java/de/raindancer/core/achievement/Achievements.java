package de.raindancer.core.achievement;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import de.raindancer.core.store.YamlStore;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final AtomicBoolean dirty = new AtomicBoolean();

    /** @param clock milliseconds; injected so "when was this earned" can be tested */
    public Achievements(Path file, LongSupplier clock) {
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
        dirty.set(true);
        announce(player, achievement);
        return true;
    }

    /** Takes one back, for a mistake. */
    public boolean revoke(UUID player, String key) {
        if (player == null || key == null || earnedBy(player).remove(normalise(key)) == null) {
            return false;
        }
        dirty.set(true);
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
        dirty.set(true);
        checkGoal(player, normalised, updated);
    }

    /** Sets it outright — for a count that is recomputed rather than added to. */
    public void setProgress(UUID player, String key, int to) {
        if (player == null || key == null) {
            return;
        }
        String normalised = normalise(key);
        progressOf(player).put(normalised, Math.max(0, to));
        dirty.set(true);
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

    public boolean isDirty() {
        return dirty.get();
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
        readPlayers(yaml.getConfigurationSection("players"));
        dirty.set(false);
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

    private void readPlayers(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            try {
                UUID player = UUID.fromString(id);
                ConfigurationSection got = entry.getConfigurationSection("earned");
                if (got != null) {
                    for (String key : got.getKeys(false)) {
                        earnedBy(player).put(unescape(key),
                                Instant.ofEpochMilli(got.getLong(key)));
                    }
                }
                ConfigurationSection towards = entry.getConfigurationSection("progress");
                if (towards != null) {
                    for (String key : towards.getKeys(false)) {
                        progressOf(player).put(unescape(key), towards.getInt(key));
                    }
                }
            } catch (RuntimeException broken) {
                log.warn("{}: player '{}' was skipped ({})",
                        file.getFileName(), id, broken.getMessage());
            }
        }
    }

    /** Writes, if anything changed. Via a temporary file, so a kill mid-write cannot truncate it. */
    public void flush() {
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
            earned.forEach((player, got) -> got.forEach((key, when) ->
                    yaml.set("players." + player + ".earned." + escape(key), when.toEpochMilli())));
            progress.forEach((player, towards) -> towards.forEach((key, count) ->
                    yaml.set("players." + player + ".progress." + escape(key), count)));
        });
        if (!written) {
            dirty.set(true);
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
