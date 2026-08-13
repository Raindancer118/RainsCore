package de.raindancer.core.world.speedrun;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.speedrun.conditions.AdvancementEndCondition;
import de.raindancer.core.world.speedrun.conditions.DeathEndCondition;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one speedrun world: its configuration, its current {@link SpeedrunSession} if it has one, and
 * the auto-reset that happens once a finished run's last participant has left.
 *
 * <h2>Why one world, not a set of them</h2>
 * {@link SpeedrunReset} already treats "the speedrun map" as a single world with no bookkeeping of
 * its own — see its javadoc for why it does not reuse {@code FarmWorlds}' {@code WorldSet} machinery.
 * This class is the same shape one level up: a lobby has a configuration and, at most, one live
 * session, never a roster of named worlds.
 *
 * <h2>Why the world state is not stored anywhere</h2>
 * It is derived: no session means {@link SpeedrunLobbyState#READY}, and otherwise it mirrors the
 * session's own {@link SpeedrunState}. A separate flag would be a second fact that could disagree
 * with the session that is sitting right here — the same reasoning {@code SpeedrunSession} uses for
 * why {@code outcome()} is read from the one field {@link SpeedrunSession#finish} writes.
 *
 * <h2>What does not survive a restart</h2>
 * A run in progress. {@link SpeedrunSession} is pure in-memory, by design (see its class javadoc),
 * and nothing here changes that — a server restarted mid-run comes back {@link SpeedrunLobbyState#READY}
 * with the map exactly as the run left it, not mid-run. Only the configuration — the world name, the
 * advancement goal, the death policy — is persisted, through {@link #settings}.
 */
public final class SpeedrunLobby {

    private static final LogChannel log = Log.of("speedrun");

    /** What {@link #start} answered, so a caller can tell a player why nothing happened. */
    public enum StartOutcome {
        /** A session now exists and is running. */
        STARTED,
        /** A run is already under way (running, paused, or finished and not yet reset). */
        NOT_READY,
        /** Neither an advancement goal nor a death policy is configured — a run that could never end. */
        NO_END_CONDITION,
        /** Nobody was handed in to run it. */
        NO_PARTICIPANTS,
        /** The configured lobby world is not currently loaded. */
        WORLD_MISSING
    }

    private final Plugin plugin;
    private final SettingsStore<SpeedrunSettings> settings;
    private final SpeedrunReset reset;
    private final SpeedrunCountdownLauncher countdownLauncher;
    private final Messages messages;

    private SpeedrunSession session;
    /** Registered fresh for every session, so a finished run's listener does not linger. */
    private SpeedrunOccupancyListener occupancy;
    /** Set the moment {@link #beginCountdown} launches one, cleared the moment it completes. */
    private boolean countingDown;

    /** No countdown, no finish announcement — {@link #beginCountdown} is not usable from this alone. */
    public SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings) {
        this(plugin, settings, new SpeedrunReset(), null, null);
    }

    public SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings, BossBars bossBars,
                         Effects effects, Messages messages) {
        this(plugin, settings, new SpeedrunReset(),
                (participants, onComplete) ->
                        new SpeedrunCountdown(plugin, bossBars, effects, participants, onComplete).begin(),
                messages);
    }

    /** For tests: a fake {@link SpeedrunCountdownLauncher} that never touches a live server. */
    SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings, SpeedrunReset reset,
                 SpeedrunCountdownLauncher countdownLauncher) {
        this(plugin, settings, reset, countdownLauncher, null);
    }

    SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings, SpeedrunReset reset,
                 SpeedrunCountdownLauncher countdownLauncher, Messages messages) {
        this.plugin = plugin;
        this.settings = settings;
        this.reset = reset;
        this.countdownLauncher = countdownLauncher;
        this.messages = messages;
    }

    public SpeedrunSettings config() {
        return settings.current();
    }

    /** For the GUI: writing a setting goes through the store, so a click and a hand-edited
     *  {@code speedrun.yml} can never disagree — same reasoning as {@code FarmWorldConfigMenu}. */
    public SettingsStore<SpeedrunSettings> settings() {
        return settings;
    }

    public Optional<SpeedrunSession> session() {
        return Optional.ofNullable(session);
    }

    /** Where the lobby is right now — see the class javadoc for why this is derived, not stored. */
    public SpeedrunLobbyState state() {
        if (countingDown) {
            return SpeedrunLobbyState.COUNTDOWN;
        }
        if (session == null) {
            return SpeedrunLobbyState.READY;
        }
        return switch (session.state()) {
            case NOT_STARTED, RUNNING -> SpeedrunLobbyState.RUNNING;
            case PAUSED -> SpeedrunLobbyState.PAUSED;
            case FINISHED -> SpeedrunLobbyState.FINISHED;
        };
    }

    /**
     * Freezes {@code participants} for a few seconds and then, if nothing has changed underneath it,
     * starts the run — see {@link SpeedrunCountdown}. The lobby reports {@link SpeedrunLobbyState#COUNTDOWN}
     * for the whole window, which is what refuses a second press of the start block mid-countdown.
     *
     * <p>Validated twice: once here, before the countdown is even shown, so a hopeless press (no end
     * condition configured, say) is refused instantly rather than after a five-second wait; and again
     * inside {@link #start} when the countdown actually completes, in case the configuration or the
     * roster changed in between.
     */
    public StartOutcome beginCountdown(Collection<UUID> participants) {
        StartOutcome problem = validate(participants);
        if (problem != null) {
            return problem;
        }
        if (countdownLauncher == null) {
            log.error("beginCountdown() was called on a SpeedrunLobby built without a countdown "
                    + "launcher — that constructor is for tests only.");
            return StartOutcome.NOT_READY;
        }
        Set<UUID> frozen = Set.copyOf(participants);
        countingDown = true;
        countdownLauncher.begin(frozen, () -> {
            countingDown = false;
            start(frozen);
        });
        return StartOutcome.STARTED;
    }

    /**
     * Starts a run with {@code participants} immediately, with no countdown — {@link #beginCountdown}
     * is what a player's click actually reaches; this is what it calls once the countdown ends, and
     * what a test calls directly to exercise the actual session-building without waiting on one.
     *
     * <p>Arms every end condition the current configuration names — an {@link AdvancementEndCondition}
     * when {@link SpeedrunSettings#hasAdvancementGoal()}, a {@link DeathEndCondition} when
     * {@link SpeedrunSettings#hasDeathCondition()} — and registers a fresh {@link SpeedrunOccupancyListener}
     * so the clock pauses while every participant is offline.
     */
    public StartOutcome start(Collection<UUID> participants) {
        StartOutcome problem = validate(participants);
        if (problem != null) {
            return problem;
        }
        SpeedrunSettings current = config();
        SpeedrunSession fresh = new SpeedrunSession(Set.copyOf(participants));
        if (current.hasAdvancementGoal()) {
            NamespacedKey key = NamespacedKey.fromString(current.advancementKey());
            if (key != null) {
                fresh.addEndCondition(new AdvancementEndCondition(plugin, key));
            } else {
                log.warn("'{}' is not a valid advancement key; the advancement goal was skipped.",
                        current.advancementKey());
            }
        }
        if (current.hasDeathCondition()) {
            DeathEndCondition.DeathPolicy policy = current.deathPolicy() == SpeedrunDeathPolicy.ALL
                    ? DeathEndCondition.DeathPolicy.ALL : DeathEndCondition.DeathPolicy.ANY;
            fresh.addEndCondition(new DeathEndCondition(plugin, policy));
        }

        session = fresh;
        occupancy = new SpeedrunOccupancyListener(fresh);
        plugin.getServer().getPluginManager().registerEvents(occupancy, plugin);
        fresh.onFinish(outcome -> announceFinish(fresh, outcome));
        fresh.start();
        return StartOutcome.STARTED;
    }

    /**
     * Tells every participant still online what ended the run and how long it took — the confirmation
     * that they raced under the settings they saw in the lobby menu, not a silent state change nobody
     * but the boss bar noticed.
     */
    private void announceFinish(SpeedrunSession finished, SpeedrunOutcome outcome) {
        if (messages == null) {
            return;
        }
        String reason = friendlyReason(outcome.reason());
        String time = formatted(outcome.elapsed());
        for (UUID participant : finished.participants()) {
            Player player = Bukkit.getPlayer(participant);
            if (player != null) {
                messages.send(player, "speedrun.finished", "reason", reason, "time", time);
            }
        }
    }

    /** {@code "advancement:minecraft:end/kill_dragon"} → the advancement's own display name, and so on. */
    private static String friendlyReason(String reason) {
        if (reason == null) {
            return "?";
        }
        if (reason.startsWith("advancement:")) {
            return SpeedrunAdvancementChooser.friendlyName(reason.substring("advancement:".length()));
        }
        if (reason.equals("death-all")) {
            return "everybody died";
        }
        if (reason.startsWith("death:")) {
            try {
                OfflinePlayer who = Bukkit.getOfflinePlayer(UUID.fromString(reason.substring("death:".length())));
                String name = who.getName();
                return (name == null ? "somebody" : name) + " died";
            } catch (IllegalArgumentException notAUuid) {
                return "somebody died";
            }
        }
        return reason;
    }

    private static String formatted(java.time.Duration elapsed) {
        long seconds = elapsed.getSeconds();
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    /** @return the reason a run cannot start right now, or {@code null} when it can. */
    private StartOutcome validate(Collection<UUID> participants) {
        if (state() != SpeedrunLobbyState.READY) {
            return StartOutcome.NOT_READY;
        }
        if (!config().hasEndCondition()) {
            return StartOutcome.NO_END_CONDITION;
        }
        if (participants == null || participants.isEmpty()) {
            return StartOutcome.NO_PARTICIPANTS;
        }
        if (world().isEmpty()) {
            return StartOutcome.WORLD_MISSING;
        }
        return null;
    }

    /**
     * Called on every quit; a no-op unless a run has finished and its last participant just left, in
     * which case the world is regenerated and the lobby returns to {@link SpeedrunLobbyState#READY}.
     *
     * <p>By id and nothing else — same reasoning as {@link SpeedrunOccupancyListener#onQuit}: the
     * quitting player may still answer {@code Bukkit.getPlayer} for part of this event's handling, so
     * they are excluded explicitly rather than trusted to already be gone from
     * {@code Bukkit.getOnlinePlayers()}.
     *
     * @param quitting the player who just quit, so they can be excluded from "is anybody still here"
     */
    public void resetIfAbandoned(UUID quitting) {
        if (state() != SpeedrunLobbyState.FINISHED) {
            return;
        }
        for (UUID participant : session.participants()) {
            if (participant.equals(quitting)) {
                continue;
            }
            if (Bukkit.getPlayer(participant) != null) {
                return;
            }
        }
        World target = world().orElse(null);
        if (occupancy != null) {
            HandlerList.unregisterAll(occupancy);
            occupancy = null;
        }
        session = null;
        if (target == null) {
            log.warn("The finished run's world '{}' is not loaded; nothing to regenerate.",
                    config().worldName());
            return;
        }
        reset.regenerate(target, SpeedrunSeed.random(), Set.of());
    }

    private Optional<World> world() {
        return Optional.ofNullable(Bukkit.getWorld(config().worldName()));
    }
}
