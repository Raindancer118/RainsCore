package de.raindancer.core.moderation.maintenance;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Maintenance mode: while it is on, only operators and the people on its own list may join.
 *
 * <p>The list is separate from the vanilla whitelist and kept while maintenance is off, so the same testers
 * are let in next time. Every change is written at once: a restart in the middle of maintenance — the usual
 * reason for it — must come back closed. Thread-safe; asked from the login thread.
 */
public final class Maintenance {

    private final YamlStore file;
    private final LongSupplier clock;
    private boolean on;
    private String reason = "";
    private long since;
    /** When those not allowed are sent off; 0 for nothing pending. Not saved: after a restart nobody is online. */
    private long kickAt;
    private final Map<UUID, String> allowed = new LinkedHashMap<>();
    private List<String> problems = List.of();

    public Maintenance(Path file, LongSupplier clock) {
        this.file = new YamlStore(file);
        this.clock = clock;
    }

    public synchronized void load() {
        YamlConfiguration yaml = file.read();
        problems = file.problems();
        allowed.clear();
        if (!problems.isEmpty()) {
            // Closed rather than open: ops still get in and can fix it, nobody else gets in by accident.
            on = true;
            reason = "";
            return;
        }
        on = yaml.getBoolean("on", false);
        reason = yaml.getString("reason", "");
        since = yaml.getLong("since", 0L);
        ConfigurationSection list = yaml.getConfigurationSection("allowed");
        if (list != null) {
            for (String key : list.getKeys(false)) {
                try {
                    allowed.put(UUID.fromString(key), list.getString(key, key));
                } catch (IllegalArgumentException notAnId) {
                    // Hand-edited; skipped.
                }
            }
        }
    }

    public synchronized List<String> problems() {
        return problems;
    }

    public synchronized boolean isOn() {
        return on;
    }

    public synchronized String reason() {
        return reason;
    }

    /** When it was switched on. */
    public synchronized long since() {
        return since;
    }

    public synchronized boolean mayJoin(UUID player, boolean operator) {
        return !on || operator || allowed.containsKey(player);
    }

    /** @return whether it was written; false means it holds only until a restart */
    public synchronized boolean turnOn(String why) {
        return turnOn(why, 0);
    }

    /**
     * Closed to joins at once; whoever is online and not allowed is sent off once {@code graceMillis} have
     * passed ({@link #kickDue}). Asked again while counting down, only the reason changes.
     */
    public synchronized boolean turnOn(String why, long graceMillis) {
        long now = clock.getAsLong();
        if (!on) {
            since = now;
        }
        if (!on || kickAt == 0) {
            kickAt = now + Math.max(0, graceMillis);
        }
        on = true;
        reason = why == null ? "" : why.strip();
        return save();
    }

    /** Whole seconds until the kick, rounded up; 0 when none is pending or it is due. */
    public synchronized long secondsLeft() {
        if (kickAt == 0) {
            return 0;
        }
        return Math.max(0, (kickAt - clock.getAsLong() + 999) / 1000);
    }

    /** The countdown has run out; true once, as asking clears it. */
    public synchronized boolean kickDue() {
        if (!on || kickAt == 0 || clock.getAsLong() < kickAt) {
            return false;
        }
        kickAt = 0;
        return true;
    }

    public synchronized boolean isCountingDown() {
        return on && kickAt != 0;
    }

    public synchronized boolean turnOff() {
        on = false;
        reason = "";
        kickAt = 0;
        return save();
    }

    public synchronized boolean allow(UUID player, String name) {
        allowed.put(player, name);
        return save();
    }

    /** @return whether they were on the list */
    public synchronized boolean disallow(UUID player) {
        boolean was = allowed.remove(player) != null;
        if (was) {
            save();
        }
        return was;
    }

    /** By id, with the name they had when they were added. */
    public synchronized Map<UUID, String> allowed() {
        return Map.copyOf(allowed);
    }

    private boolean save() {
        boolean on = this.on;
        String reason = this.reason;
        long since = this.since;
        Map<UUID, String> list = new LinkedHashMap<>(allowed);
        boolean saved = file.write(yaml -> {
            yaml.set("on", on);
            yaml.set("reason", reason);
            yaml.set("since", since);
            list.forEach((id, name) -> yaml.set("allowed." + id, name));
        });
        if (saved) {
            problems = List.of();
        }
        return saved;
    }
}
