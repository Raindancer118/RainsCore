package de.raindancer.core.moderation.players;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Doing things to somebody's body that {@link PlayerAdmin} does not: their breath, their size, how fast
 * they move, a push through the air, a clean slate, a bang.
 *
 * <h2>Why the ranges live here</h2>
 * Every one of these has an edge the game throws at or quietly misbehaves past: a walk speed over 1.0
 * throws, a scale over 16 is clamped without a word, a launch at power 50 throws the player through
 * chunks that never load, an explosion of 200 stops the server. A command that asks here gets "that
 * is outside what the game allows" instead.
 */
public final class PlayerBody {

    /** What a wipe can take. */
    public enum Wipe {
        INVENTORY, ENDER_CHEST, ADVANCEMENTS, EXPERIENCE, EFFECTS;

        /** What {@code /wipe} takes when nobody says otherwise: inventory, advancements and experience. */
        public static final Set<Wipe> STANDARD =
                Collections.unmodifiableSet(EnumSet.of(INVENTORY, ADVANCEMENTS, EXPERIENCE));
    }

    /** Which way a launch goes. */
    public enum Launch {
        /** Straight up. */
        UP,
        /** The way they face, lifted a little so friction does not eat it. */
        FORWARD,
        /** The way they face, all of it — including straight down if they look down. */
        LOOK
    }

    public static final double MIN_SCALE = 0.0625;
    public static final double MAX_SCALE = 16;
    public static final int MAX_SPEED_LEVEL = 10;
    public static final double MAX_LAUNCH = 10;
    public static final float MAX_EXPLOSION = 16;
    public static final int MAX_FIRE_SECONDS = 3600;
    private static final float VANILLA_WALK = 0.2f;
    private static final float VANILLA_FLY = 0.1f;
    private static final double LIFT = 0.5;

    private final PlayerBodySink sink;

    public PlayerBody(PlayerBodySink sink) {
        this.sink = sink;
    }

    public Optional<BodyState> stateOf(UUID who) {
        return who == null ? Optional.empty() : sink.stateOf(who);
    }

    // ------------------------------------------------------------------ air

    public Outcome breathe(UUID who) {
        Optional<BodyState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (state.get().air() >= state.get().maxAir()) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.air(who, state.get().maxAir());
        return Outcome.DONE;
    }

    /** Empties their lungs and does {@code damage} as drowning would — refused where it would kill. */
    public Outcome drown(UUID who, double damage) {
        Optional<BodyState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (damage >= state.get().health()) {
            return Outcome.WOULD_KILL;
        }
        sink.air(who, 0);
        if (damage > 0) {
            sink.drowningDamage(who, damage);
        }
        return Outcome.DONE;
    }

    // ------------------------------------------------------------------ size and speed

    public Outcome scale(UUID who, double scale) {
        Optional<BodyState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (!(scale >= MIN_SCALE && scale <= MAX_SCALE)) {
            return Outcome.OUT_OF_RANGE;
        }
        if (Math.abs(state.get().scale() - scale) < 1e-9) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.scale(who, scale);
        return Outcome.DONE;
    }

    /** Level 1 is vanilla, 0 cannot move, 10 is the game's maximum. */
    public static float walkSpeedFor(int level) {
        return Math.min(1f, VANILLA_WALK * level);
    }

    public static float flySpeedFor(int level) {
        return Math.min(1f, VANILLA_FLY * level);
    }

    public Outcome walkSpeed(UUID who, int level) {
        return speed(who, level, true);
    }

    public Outcome flySpeed(UUID who, int level) {
        return speed(who, level, false);
    }

    private Outcome speed(UUID who, int level, boolean walking) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (level < 0 || level > MAX_SPEED_LEVEL) {
            return Outcome.OUT_OF_RANGE;
        }
        if (walking) {
            sink.walkSpeed(who, walkSpeedFor(level));
        } else {
            sink.flySpeed(who, flySpeedFor(level));
        }
        return Outcome.DONE;
    }

    public Outcome resetSpeeds(UUID who) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        sink.walkSpeed(who, VANILLA_WALK);
        sink.flySpeed(who, VANILLA_FLY);
        return Outcome.DONE;
    }

    // ------------------------------------------------------------------ through the air

    public Outcome launch(UUID who, Launch way, double power) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (power <= 0) {
            return Outcome.NOTHING_TO_DO;
        }
        if (power > MAX_LAUNCH) {
            return Outcome.OUT_OF_RANGE;
        }
        switch (way == null ? Launch.UP : way) {
            case UP -> sink.launch(who, 0, power, 0, false);
            case FORWARD -> sink.launch(who, 0, LIFT, power, true);
            case LOOK -> sink.launch(who, 0, 0, power, true);
        }
        return Outcome.DONE;
    }

    // ------------------------------------------------------------------ the rest

    public Outcome wipe(UUID who, Set<Wipe> parts) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (parts == null || parts.isEmpty()) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.wipe(who, Collections.unmodifiableSet(EnumSet.copyOf(parts)));
        return Outcome.DONE;
    }

    public Outcome explode(UUID who, float power, boolean breakBlocks, boolean fire) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (power <= 0) {
            return Outcome.NOTHING_TO_DO;
        }
        if (power > MAX_EXPLOSION) {
            return Outcome.OUT_OF_RANGE;
        }
        sink.explode(who, power, breakBlocks, fire);
        return Outcome.DONE;
    }

    public Outcome ignite(UUID who, int seconds) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (seconds <= 0) {
            return Outcome.NOTHING_TO_DO;
        }
        if (seconds > MAX_FIRE_SECONDS) {
            return Outcome.OUT_OF_RANGE;
        }
        sink.ignite(who, seconds * 20);
        return Outcome.DONE;
    }
}
