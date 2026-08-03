package de.raindancer.core.ui.effect;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Every sound and every particle any plugin makes.
 *
 * <h2>Why this is Core's, and not a wrapper</h2>
 * Two reasons. The first is that nine plugins each choosing their own click sound gives a server nine
 * different clicks, and an owner who wants them quieter has to edit nine plugins — if they can find
 * them, which they cannot, because a sound is one line buried in a menu handler. Asking by
 * <em>meaning</em> ({@link Cues#NO}) rather than by sound, and binding that meaning in one place, is
 * the difference between a server that sounds like itself and one that sounds like a plugin folder.
 *
 * <p>The second is the same collision as the action bar and the sidebar. A plugin playing a cue on
 * every tick of something is a plugin deafening a player, and it never finds out, because from inside
 * that plugin it is one sound. So the same cue to the same player twice in a moment is played once.
 *
 * <h2>Using it</h2>
 * <pre>{@code
 * core.effects().play(player.getUniqueId(), Cues.NO);
 * core.effects().playAt(world, x, y, z, Cues.TELEPORT);
 *
 * // a plugin's own, which anybody may then rebind
 * core.effects().define("ghastlines:whoosh", Effect.of(new SoundCue("entity.ghast.shoot", .8f, 1.2f)));
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. Whether the sink is depends on the sink; the Bukkit one schedules itself onto
 * the right thread for the region.
 */
public final class Effects {

    private static final LogChannel log = Log.of("effects");

    /**
     * How close together the same cue has to be to count as a repeat.
     *
     * <p>Long enough to swallow a per-tick loop, short enough that a player clicking quickly through
     * a menu still hears every click.
     */
    private static final Duration DEFAULT_GAP = Duration.ofMillis(120);

    private final EffectSink sink;
    private final LongSupplier clock;

    private final Map<String, Effect> bound = new ConcurrentHashMap<>();
    /** When each player last heard each cue. Keyed by both, so cues do not suppress each other. */
    private final Map<String, Long> lastPlayed = new ConcurrentHashMap<>();
    /** Cues somebody asked for that nobody had defined. Said once each, not once per call. */
    private final Set<String> missing = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled = true;
    private volatile long gapMillis = DEFAULT_GAP.toMillis();

    /** @param clock milliseconds; injected so the repeat window can be tested without waiting */
    public Effects(EffectSink sink, LongSupplier clock) {
        this.sink = sink;
        this.clock = clock;
        defineDefaults();
    }

    /**
     * What Core ships with.
     *
     * <p>Vanilla sounds on purpose: a default that needs a resource pack is a default that is silent
     * on most servers, and silence is indistinguishable from something being broken.
     */
    private void defineDefaults() {
        // Answers
        bound.put(Cues.OK, Effect.of(new SoundCue("entity.experience_orb.pickup", 0.6f, 1.6f)));
        bound.put(Cues.NO, Effect.of(new SoundCue("block.note_block.bass", 0.7f, 0.7f)));
        bound.put(Cues.WARN, Effect.of(new SoundCue("block.note_block.pling", 0.6f, 0.8f)));
        bound.put(Cues.ERROR, Effect.of(new SoundCue("entity.item.break", 0.7f, 0.8f)));
        bound.put(Cues.NOTIFY, Effect.of(new SoundCue("block.note_block.chime", 0.6f, 1.5f)));

        // Menus. Quiet on purpose: these are heard hundreds of times an hour.
        bound.put(Cues.CLICK, Effect.of(new SoundCue("ui.button.click", 0.35f, 1.0f)));
        bound.put(Cues.OPEN, Effect.of(new SoundCue("block.barrel.open", 0.4f, 1.4f)));
        bound.put(Cues.CLOSE, Effect.of(new SoundCue("block.barrel.close", 0.4f, 1.4f)));
        bound.put(Cues.PAGE, Effect.of(new SoundCue("item.book.page_turn", 0.5f, 1.1f)));

        // Moving about
        bound.put(Cues.TELEPORT, new Effect(new SoundCue("entity.enderman.teleport", 0.6f, 1.2f),
                new ParticleCue("PORTAL", 24, 0.4, 0.6, 0.4, 0.06)));
        bound.put(Cues.COUNTDOWN, Effect.of(new SoundCue("block.note_block.hat", 0.5f, 1.2f)));
        bound.put(Cues.COUNTDOWN_DONE,
                Effect.of(new SoundCue("block.note_block.bell", 0.7f, 1.6f)));
        bound.put(Cues.ENTER, Effect.of(new SoundCue("block.note_block.harp", 0.4f, 1.5f)));
        bound.put(Cues.LEAVE, Effect.of(new SoundCue("block.note_block.harp", 0.4f, 1.0f)));

        // Things happening to you
        bound.put(Cues.EARNED, new Effect(new SoundCue("entity.player.levelup", 0.7f, 1.4f),
                new ParticleCue("HAPPY_VILLAGER", 12, 0.4, 0.5, 0.4, 0.02)));
        bound.put(Cues.REWARD, Effect.of(new SoundCue("entity.item.pickup", 0.7f, 1.2f)));
        bound.put(Cues.HEAL, new Effect(new SoundCue("entity.player.burp", 0.4f, 1.6f),
                new ParticleCue("HEART", 6, 0.4, 0.5, 0.4, 0.01)));
        bound.put(Cues.HURT, Effect.of(new SoundCue("entity.player.hurt", 0.6f, 1.0f)));
        bound.put(Cues.SUMMON, new Effect(new SoundCue("entity.illusioner_cast_spell", 0.6f, 1.2f),
                new ParticleCue("CLOUD", 20, 0.4, 0.3, 0.4, 0.03)));
        bound.put(Cues.VANISH, new Effect(new SoundCue("entity.generic_extinguish_fire", 0.5f, 1.4f),
                new ParticleCue("SMOKE", 16, 0.3, 0.3, 0.3, 0.02)));
        bound.put(Cues.MAGIC, new Effect(new SoundCue("block.enchantment_table.use", 0.6f, 1.2f),
                new ParticleCue("ENCHANT", 30, 0.5, 0.8, 0.5, 0.5)));
        bound.put(Cues.ABILITY, Effect.of(new SoundCue("entity.evoker.cast_spell", 0.6f, 1.3f)));
        bound.put(Cues.COOLDOWN, Effect.of(new SoundCue("block.dispenser.fail", 0.6f, 1.0f)));
    }

    // ---------------------------------------------------------------------------- the vocabulary

    /**
     * Binds a name to what it does, replacing whatever it was.
     *
     * <p>How a plugin adds its own, and how a server owner changes one that already exists. Both are
     * the same call on purpose: there is nothing special about Core's own cues.
     */
    public void define(String cue, Effect effect) {
        if (cue == null || cue.isBlank() || effect == null) {
            return;
        }
        bound.put(cue.trim(), effect);
        missing.remove(cue.trim());
    }

    /** Forgets a cue entirely. Prefer binding {@link Effect#silence()} — see there for why. */
    public void undefine(String cue) {
        if (cue != null) {
            bound.remove(cue.trim());
        }
    }

    public boolean isDefined(String cue) {
        return cue != null && bound.containsKey(cue.trim());
    }

    /** What a cue is currently bound to. */
    public Optional<Effect> boundTo(String cue) {
        return cue == null ? Optional.empty() : Optional.ofNullable(bound.get(cue.trim()));
    }

    /** Every cue anybody has defined, in the order they were defined. */
    public Map<String, Effect> all() {
        return new LinkedHashMap<>(bound);
    }

    /** Cues somebody asked for that nobody had defined — usually a typo, always worth knowing. */
    public List<String> problems() {
        return missing.stream().map(cue -> "nothing is bound to '" + cue + "'").sorted().toList();
    }

    // ---------------------------------------------------------------------------- settings

    /** Whether anything is played at all. */
    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** How close together the same cue counts as a repeat. Zero switches the suppression off. */
    public void minimumGap(Duration gap) {
        this.gapMillis = gap == null || gap.isNegative() ? 0 : gap.toMillis();
    }

    // ---------------------------------------------------------------------------- playing

    /** Plays a cue for one player, where they are. */
    public void play(UUID player, String cue) {
        if (player == null || !enabled) {
            return;
        }
        Effect effect = lookUp(cue);
        if (effect == null || effect.isSilent() || tooSoon(player, cue)) {
            return;
        }
        if (effect.sound() != null) {
            sink.toPlayer(player, effect.sound());
        }
        if (effect.particles() != null && !effect.particles().isNothing()) {
            sink.toPlayer(player, effect.particles());
        }
    }

    /**
     * Plays a cue at a place, for everybody near enough.
     *
     * <p>Not throttled against a player's own cues: somebody else teleporting nearby is a different
     * event from your own teleport, and folding them together would swallow one of them.
     */
    public void playAt(String world, double x, double y, double z, String cue) {
        if (world == null || !enabled) {
            return;
        }
        Effect effect = lookUp(cue);
        if (effect == null || effect.isSilent()) {
            return;
        }
        if (effect.sound() != null) {
            sink.atPlace(world, x, y, z, effect.sound());
        }
        if (effect.particles() != null && !effect.particles().isNothing()) {
            sink.atPlace(world, x, y, z, effect.particles());
        }
    }

    /** The same for several players at once — one refusal heard by a whole party. */
    public void playForAll(Iterable<UUID> players, String cue) {
        if (players == null) {
            return;
        }
        players.forEach(player -> play(player, cue));
    }

    // ---------------------------------------------------------------------------- stopping

    /**
     * Stops a cue this player is hearing.
     *
     * <p>Needed as soon as anything lasts longer than an instant — a jukebox, a countdown drone, a
     * boss theme. Without it the only way to end one is to wait, and a player who reconnects mid-cue
     * hears it again on top of itself.
     *
     * <p>Sounds only. Particles are drawn and gone; there is nothing to stop, and this deliberately
     * does not pretend there is.
     */
    public void stop(UUID player, String cue) {
        if (player == null) {
            return;
        }
        Effect effect = lookUp(cue);
        if (effect == null || effect.sound() == null || effect.sound().isSilent()) {
            return;
        }
        sink.stopForPlayer(player, effect.sound().key());
        // Forgotten rather than left behind, so a plugin that stops a cue and starts it again is
        // not silently refused by the repeat window it just filled.
        lastPlayed.remove(player + "/" + cue.trim());
    }

    /** Stops everything this player is hearing from the server. */
    public void stopAll(UUID player) {
        if (player != null) {
            sink.stopAllForPlayer(player);
            forget(player);
        }
    }

    // ---------------------------------------------------------------------------- bookkeeping

    /**
     * Forgets a player — for one who has left.
     *
     * <p>Without it there is one entry per player per cue kept for ever, which is a leak that grows
     * with every player who has ever joined.
     */
    public void forget(UUID player) {
        if (player == null) {
            return;
        }
        String prefix = player + "/";
        lastPlayed.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** How much is being remembered, for a test and for a health check. */
    public int remembering() {
        return lastPlayed.size();
    }

    private Effect lookUp(String cue) {
        if (cue == null || cue.isBlank()) {
            return null;
        }
        String name = cue.trim();
        Effect effect = bound.get(name);
        if (effect == null && missing.add(name)) {
            // Once, not once per call: a cue asked for on every tick would otherwise fill the log
            // faster than the thing it is complaining about.
            log.warn("Nothing is bound to the effect '{}'; nothing was played.", name);
        }
        return effect;
    }

    /**
     * Whether this player heard this cue a moment ago.
     *
     * <p>The time is recorded only when the cue is actually going to play. Writing it on every
     * attempt — which this did first — means a plugin looping faster than the gap keeps pushing the
     * window forward and the cue is never heard again at all: the suppression stops being "at most
     * one every 120ms" and becomes "never", silently.
     *
     * <p>Done with {@code compute} so the check and the write are one step; two threads asking at
     * the same instant must not both be told yes.
     */
    private boolean tooSoon(UUID player, String cue) {
        long gap = gapMillis;
        if (gap <= 0) {
            return false;
        }
        String key = player + "/" + cue.trim();
        long now = clock.getAsLong();
        boolean[] suppress = {false};
        lastPlayed.compute(key, (ignored, previous) -> {
            if (previous != null && now - previous < gap) {
                suppress[0] = true;
                return previous;
            }
            return now;
        });
        return suppress[0];
    }

    /** Everything currently bound, as lines for a banner or a menu. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        bound.forEach((cue, effect) -> lines.add(cue + " → "
                + (effect.sound() == null ? "no sound" : effect.sound().key())
                + (effect.particles() == null ? "" : " + " + effect.particles().particle())));
        lines.sort(String::compareTo);
        return lines;
    }
}
