package de.raindancer.core.moderation;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import de.raindancer.core.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Bans, mutes, freezes, and the record of who did what.
 *
 * <h2>Why this is here rather than in a moderation plugin</h2>
 * Every plugin that can refuse a player something eventually grows its own way of remembering that
 * it refused them. Separately, that means several files that disagree about whether somebody is
 * muted, several answers to "when does this end", and nowhere to look when a player asks why they
 * cannot build. Any plugin can ask this one, and the claims module can freeze somebody's hands
 * without having an opinion about whether they should be on the server at all.
 *
 * <h2>Nothing is ever deleted</h2>
 * Lifting a ban adds the lifting to it; the ban stays. That is what makes a second offence
 * answerable, and it is the difference between this and a set of flags.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. Each player's record is a {@link CopyOnWriteArrayList} — punishments are
 * read constantly (every join, every chat message) and written rarely, which is exactly what that
 * list is for.
 */
public final class Punishments {

    private static final LogChannel log = Log.of("moderation");

    private final Path file;
    private final YamlStore store;
    private final LongSupplier clock;
    private final java.util.Map<UUID, CopyOnWriteArrayList<Punishment>> byPlayer =
            new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();

    /** @param clock milliseconds; injected so expiry can be tested without waiting for it */
    public Punishments(Path file, LongSupplier clock) {
        this.file = file;
        this.store = new YamlStore(file);
        this.clock = clock;
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.getAsLong());
    }

    // ---------------------------------------------------------------------------- punishing

    /**
     * Records a punishment, replacing any of the same kind that is still in force.
     *
     * @param length how long for; null means until somebody lifts it
     * @return the punishment, so the caller can tell the player about it
     */
    public Punishment punish(UUID target, PunishmentKind kind, UUID moderator, String reason,
                             Duration length) {
        Instant now = now();
        // The old one is lifted rather than dropped, so the history still shows both. A moderator
        // extending a mute has done two things and the record should say so.
        active(target, kind).ifPresent(existing ->
                replace(target, existing, existing.lifted(moderator, "replaced", now)));

        Punishment given = Punishment.given(target, kind, moderator, reason, now, length);
        recordsOf(target).add(given);
        dirty.set(true);
        log.info("{} was {} by {} for '{}' ({})", target, kind.past(), moderator, given.reason(),
                given.length());
        return given;
    }

    /**
     * Ends a punishment early.
     *
     * @return whether there was one to end
     */
    public boolean lift(UUID target, PunishmentKind kind, UUID moderator, String reason) {
        Optional<Punishment> existing = active(target, kind);
        if (existing.isEmpty()) {
            return false;
        }
        replace(target, existing.get(), existing.get().lifted(moderator, reason, now()));
        dirty.set(true);
        log.info("{}'s {} was lifted by {} ('{}')", target, kind.name().toLowerCase(java.util.Locale.ROOT),
                moderator, reason);
        return true;
    }

    private void replace(UUID target, Punishment old, Punishment updated) {
        CopyOnWriteArrayList<Punishment> records = recordsOf(target);
        int at = records.indexOf(old);
        if (at >= 0) {
            records.set(at, updated);
        }
    }

    // ---------------------------------------------------------------------------- asking

    /** Whether this punishment applies to this player right now. */
    public boolean isActive(UUID player, PunishmentKind kind) {
        return active(player, kind).isPresent();
    }

    /** The punishment of this kind in force, if any. */
    public Optional<Punishment> active(UUID player, PunishmentKind kind) {
        if (player == null || kind == null) {
            return Optional.empty();
        }
        Instant now = now();
        return recordsOf(player).stream()
                .filter(punishment -> punishment.kind() == kind)
                .filter(punishment -> punishment.isActiveAt(now))
                .findFirst();
    }

    /** How much longer a punishment has, for telling the player. */
    public Optional<Duration> remaining(UUID player, PunishmentKind kind) {
        return active(player, kind).flatMap(punishment -> punishment.remainingAt(now()));
    }

    /** Everything that has ever happened to this player, newest first. */
    public List<Punishment> history(UUID player) {
        if (player == null) {
            return List.of();
        }
        List<Punishment> records = new ArrayList<>(recordsOf(player));
        records.sort(Comparator.comparing(Punishment::givenAt).reversed());
        return List.copyOf(records);
    }

    /** Everything in force right now, for a moderator's screen. */
    public List<Punishment> allActive() {
        Instant now = now();
        return byPlayer.values().stream()
                .flatMap(List::stream)
                .filter(punishment -> punishment.isActiveAt(now))
                .toList();
    }

    /** The same, of one kind — the ban list, the mute list. */
    public List<Punishment> allActive(PunishmentKind kind) {
        return allActive().stream().filter(punishment -> punishment.kind() == kind).toList();
    }

    private CopyOnWriteArrayList<Punishment> recordsOf(UUID player) {
        return byPlayer.computeIfAbsent(player, key -> new CopyOnWriteArrayList<>());
    }

    // ---------------------------------------------------------------------------- the file

    public boolean isDirty() {
        return dirty.get();
    }

    public void load() {
        byPlayer.clear();
        if (!store.exists()) {
            dirty.set(false);
            return;
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            // Deliberately loud: a moderation file that will not load means every ban on the server
            // has silently stopped applying, which somebody has to know about now.
            log.fatal("Could not read {} ({}). Nobody is banned or muted this session until this is "
                    + "fixed.", file, String.join("; ", store.problems()));
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("punishments");
        if (section == null) {
            dirty.set(false);
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            try {
                Punishment punishment = read(id, entry);
                recordsOf(punishment.target()).add(punishment);
            } catch (RuntimeException broken) {
                log.warn("{}: punishment '{}' could not be read and was skipped ({})",
                        file.getFileName(), id, broken.getMessage());
            }
        }
        dirty.set(false);
    }

    private static Punishment read(String id, ConfigurationSection entry) {
        return new Punishment(id,
                UUID.fromString(entry.getString("target", "")),
                PunishmentKind.valueOf(entry.getString("kind", "BAN")),
                uuid(entry.getString("moderator")),
                entry.getString("reason"),
                Instant.ofEpochMilli(entry.getLong("given-at")),
                instant(entry, "ends-at"),
                uuid(entry.getString("lifted-by")),
                entry.getString("lift-reason"),
                instant(entry, "lifted-at"));
    }

    private static Instant instant(ConfigurationSection entry, String key) {
        return entry.contains(key) ? Instant.ofEpochMilli(entry.getLong(key)) : null;
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    /** Writes, if anything changed. Via a temporary file, so a kill mid-write cannot truncate it. */
    public void flush() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        var snapshot = List.copyOf(byPlayer.values());
        boolean written = store.write(yaml -> {
            for (List<Punishment> records : snapshot) {
                for (Punishment punishment : List.copyOf(records)) {
                    String path = "punishments." + punishment.id() + ".";
                    yaml.set(path + "target", punishment.target().toString());
                    yaml.set(path + "kind", punishment.kind().name());
                    yaml.set(path + "reason", punishment.reason());
                    yaml.set(path + "given-at", punishment.givenAt().toEpochMilli());
                    if (punishment.moderator() != null) {
                        yaml.set(path + "moderator", punishment.moderator().toString());
                    }
                    if (punishment.endsAt() != null) {
                        yaml.set(path + "ends-at", punishment.endsAt().toEpochMilli());
                    }
                    punishment.liftedBy().ifPresent(by -> yaml.set(path + "lifted-by", by.toString()));
                    punishment.liftReason().ifPresent(why -> yaml.set(path + "lift-reason", why));
                    if (punishment.liftedAt() != null) {
                        yaml.set(path + "lifted-at", punishment.liftedAt().toEpochMilli());
                    }
                }
            }
        });
        if (!written) {
            dirty.set(true);
        }
    }
}
