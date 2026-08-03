package de.raindancer.core.player;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Doing things to a player, from a management screen.
 *
 * <h2>Why this is not a wrapper round {@code setHealth}</h2>
 * Because the mistakes are all in the edges and every plugin makes them again. Healing above the
 * maximum throws. Damaging for more than somebody has kills them, from a button labelled "damage".
 * Setting a speed effect without clearing the last one stacks them. Feeding past twenty throws.
 * Acting on somebody who logged out a second ago throws. Each of those is an exception in front of a
 * moderator, or a dead player who was meant to be nudged.
 *
 * <p>So every action answers an {@link Outcome} — done, nothing to do, they are gone, that would
 * have killed them — and none of them throws.
 *
 * <h2>What is here and what is not</h2>
 * The things a management screen does to somebody who is <em>present</em>: health, food, effects,
 * flight, gamemode, fire, kicking. Banning and muting are deliberately not here: they are records
 * that outlive a session and belong to {@code moderation.Punishments}, which already keeps a history
 * and enforces them. A management screen calls both.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread as far as this goes; the sink decides where its own work happens.
 */
public final class PlayerAdmin {

    private static final LogChannel log = Log.of("players");

    /** The highest amplifier the protocol carries. Beyond this the client sees nothing. */
    private static final int MAX_LEVEL = 255;

    /** The gamemodes there are. A name outside this is a typo, not a mode. */
    private static final Set<String> GAMEMODES =
            Set.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR");

    private final PlayerAdminSink sink;

    public PlayerAdmin(PlayerAdminSink sink) {
        this.sink = sink;
    }

    // ---------------------------------------------------------------------------- health

    /** Fills somebody up. */
    public Outcome heal(UUID who) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (state.get().isFull()) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.health(who, state.get().maxHealth());
        return Outcome.DONE;
    }

    /** Heals by an amount, stopping at their maximum rather than throwing past it. */
    public Outcome heal(UUID who, double amount) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (amount <= 0) {
            return Outcome.NOTHING_TO_DO;
        }
        if (state.get().isFull()) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.health(who, Math.min(state.get().maxHealth(), state.get().health() + amount));
        return Outcome.DONE;
    }

    /**
     * Takes health away — unless it would kill them.
     *
     * <p>A button labelled "damage" that kills somebody is a button that lied, so this refuses and
     * says so. {@link #kill} is how you mean it.
     */
    public Outcome damage(UUID who, double amount) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (amount <= 0) {
            return Outcome.NOTHING_TO_DO;
        }
        if (amount >= state.get().health()) {
            return Outcome.WOULD_KILL;
        }
        sink.health(who, state.get().health() - amount);
        return Outcome.DONE;
    }

    /** Kills them, deliberately. */
    public Outcome kill(UUID who) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        sink.health(who, 0);
        log.info("{} was killed from a management screen.", who);
        return Outcome.DONE;
    }

    // ---------------------------------------------------------------------------- food

    /** Fills their food bar. */
    public Outcome feed(UUID who) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (state.get().isFed()) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.food(who, 20);
        return Outcome.DONE;
    }

    /** Empties it. */
    public Outcome starve(UUID who) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (state.get().food() <= 0) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.food(who, 0);
        return Outcome.DONE;
    }

    /** Sets it to something in between. */
    public Outcome food(UUID who, int level) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (level < 0 || level > 20) {
            return Outcome.OUT_OF_RANGE;
        }
        sink.food(who, level);
        return Outcome.DONE;
    }

    // ---------------------------------------------------------------------------- effects

    /** Faster. Level 0 takes it off. */
    public Outcome speed(UUID who, int level, Duration lasting) {
        return give(who, "SPEED", level, lasting);
    }

    /** Slower. Level 0 takes it off. */
    public Outcome slowness(UUID who, int level, Duration lasting) {
        return give(who, "SLOWNESS", level, lasting);
    }

    /** Stronger. */
    public Outcome strength(UUID who, int level, Duration lasting) {
        return give(who, "STRENGTH", level, lasting);
    }

    /** Able to see in the dark. */
    public Outcome nightVision(UUID who, Duration lasting) {
        return give(who, "NIGHT_VISION", 1, lasting);
    }

    /** Unable to be hurt by much. */
    public Outcome resistance(UUID who, int level, Duration lasting) {
        return give(who, "RESISTANCE", level, lasting);
    }

    /**
     * Any effect at all.
     *
     * <p>Two rules that every hand-rolled version gets wrong. A level of zero <em>removes</em> the
     * effect rather than applying amplifier zero — which is level one, so an "off" button that
     * speeds somebody up. And an effect already on is cleared first rather than stacked, because two
     * speeds at once leaves a player moving at a speed nobody chose.
     *
     * @param level   1 upwards; 0 takes it away
     * @param lasting null for one that does not expire
     */
    public Outcome give(UUID who, String effect, int level, Duration lasting) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (effect == null || effect.isBlank()) {
            return Outcome.NOT_UNDERSTOOD;
        }
        String name = effect.trim().toUpperCase(Locale.ROOT);
        if (level < 0 || level > MAX_LEVEL) {
            return Outcome.OUT_OF_RANGE;
        }
        if (level == 0) {
            sink.clearEffect(who, name);
            return Outcome.DONE;
        }
        // Cleared first, always. Applying over an existing one is version-dependent — sometimes the
        // stronger wins, sometimes the newer — and "sometimes" is not something to build a menu on.
        sink.clearEffect(who, name);
        sink.effect(who, name, level, lasting);
        return Outcome.DONE;
    }

    /** Takes one effect away. */
    public Outcome take(UUID who, String effect) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (effect == null || effect.isBlank()) {
            return Outcome.NOT_UNDERSTOOD;
        }
        sink.clearEffect(who, effect.trim().toUpperCase(Locale.ROOT));
        return Outcome.DONE;
    }

    /** Takes everything off — the milk-bucket button. */
    public Outcome cure(UUID who) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        sink.clearAllEffects(who);
        return Outcome.DONE;
    }

    // ---------------------------------------------------------------------------- the rest

    /** Whether they may fly. */
    public Outcome flight(UUID who, boolean allowed) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (state.get().flying() == allowed) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.allowFlight(who, allowed);
        return Outcome.DONE;
    }

    /** Turns flight on if it is off, and off if it is on. */
    public Outcome toggleFlight(UUID who) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        return flight(who, !state.get().flying());
    }

    /** Their gamemode, by name, in any case. */
    public Outcome gamemode(UUID who, String mode) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (mode == null || mode.isBlank()) {
            return Outcome.NOT_UNDERSTOOD;
        }
        String wanted = mode.trim().toUpperCase(Locale.ROOT);
        if (!GAMEMODES.contains(wanted)) {
            return Outcome.NOT_UNDERSTOOD;
        }
        if (wanted.equals(state.get().gamemode())) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.gamemode(who, wanted);
        return Outcome.DONE;
    }

    /** The gamemodes there are, for a menu that offers them. */
    public static List<String> gamemodes() {
        return List.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR");
    }

    /**
     * Disconnects somebody, with a reason.
     *
     * <p>A blank reason becomes a real sentence: a player staring at an empty disconnect screen has
     * been told nothing, and will ask anyway.
     */
    public Outcome kick(UUID who, String reason) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        String said = reason == null || reason.isBlank()
                ? "You were disconnected by a moderator." : reason.trim();
        sink.kick(who, said);
        log.info("{} was kicked: {}", who, said);
        return Outcome.DONE;
    }

    /** Puts them out. */
    public Outcome extinguish(UUID who) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        sink.extinguish(who);
        return Outcome.DONE;
    }

    /** How somebody is, for a screen that wants to draw it. */
    public Optional<PlayerState> stateOf(UUID who) {
        return who == null ? Optional.empty() : sink.stateOf(who);
    }
}
