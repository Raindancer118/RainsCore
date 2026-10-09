package de.raindancer.core.social.presence;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How long everybody has played on this server, and how much of it they were not away — for profiles, staff
 * screens, and anything that weighs "lately" by play rather than by the calendar.
 *
 * <p>Counted a minute at a time while somebody is online, seeded once from the game's own statistic so
 * players from before this existed do not start at zero. Thread-safe; written by {@link #save} from wherever
 * the caller likes.
 */
public final class Playtime {

    private record Entry(long minutes, long active) {
    }

    private final YamlStore file;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public Playtime(Path file) {
        this.file = new YamlStore(file);
    }

    public void load() {
        YamlConfiguration yaml = file.read();
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            try {
                entries.put(UUID.fromString(key), new Entry(players.getLong(key + ".minutes"),
                        players.getLong(key + ".active")));
            } catch (IllegalArgumentException notAnId) {
                // A line somebody typed by hand; skipped rather than failing everybody's playtime.
            }
        }
    }

    /** Starts counting somebody from what the game recorded ({@code PLAY_ONE_MINUTE}, in ticks) — only the first time. */
    public void seed(UUID player, long ticksPlayed) {
        long minutes = Math.max(0, ticksPlayed) / 20 / 60;
        if (entries.putIfAbsent(player, new Entry(minutes, minutes)) == null) {
            dirty = true;
        }
    }

    /** One more minute online. @param away whether they were away from the keyboard for it */
    public void minute(UUID player, boolean away) {
        entries.merge(player, new Entry(1, away ? 0 : 1),
                (had, one) -> new Entry(had.minutes() + one.minutes(), had.active() + one.active()));
        dirty = true;
    }

    public boolean isKnown(UUID player) {
        return entries.containsKey(player);
    }

    public long minutes(UUID player) {
        Entry entry = entries.get(player);
        return entry == null ? 0 : entry.minutes();
    }

    /** Minutes online and not away. */
    public long activeMinutes(UUID player) {
        Entry entry = entries.get(player);
        return entry == null ? 0 : entry.active();
    }

    /** Minutes online but away from the keyboard. */
    public long awayMinutes(UUID player) {
        return Math.max(0, minutes(player) - activeMinutes(player));
    }

    public Duration playedAway(UUID player) {
        return Duration.ofMinutes(awayMinutes(player));
    }

    public Duration played(UUID player) {
        return Duration.ofMinutes(minutes(player));
    }

    public Duration playedActively(UUID player) {
        return Duration.ofMinutes(activeMinutes(player));
    }

    public boolean isDirty() {
        return dirty;
    }

    /** @return whether it reached disk */
    public boolean save() {
        dirty = false;
        Map<UUID, Entry> snapshot = new HashMap<>(entries);
        boolean saved = file.write(yaml -> snapshot.forEach((id, entry) -> {
            yaml.set("players." + id + ".minutes", entry.minutes());
            yaml.set("players." + id + ".active", entry.active());
        }));
        if (!saved) {
            dirty = true;
        }
        return saved;
    }
}
