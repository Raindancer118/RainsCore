package de.raindancer.core.world.teleport;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effect;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.effect.ParticleCue;
import de.raindancer.core.ui.effect.ParticleSequence;
import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.core.ui.effect.SoundCue;
import de.raindancer.core.ui.effect.SoundSequence;

import java.util.Optional;
import java.util.UUID;

/**
 * The sound and the sparkle of every teleport on the server, whichever plugin sent it.
 *
 * <p>One per server, held by Core, because every plugin builds its own {@link Travel} — homes, warps
 * and teleport requests each have one — and a player's chosen arrival sound has to follow them through
 * all of them. {@link Travel} asks this at departure, every few ticks of the warm-up and on arrival.
 *
 * <p>The server's half is three cues an owner can rebind like any other ({@link Cues#TELEPORT_DEPART},
 * {@link Cues#TELEPORT_WAIT}, {@link Cues#TELEPORT}); the player's half is whatever {@link TravelLooks}
 * one plugin registers.
 */
public final class TravelShow {

    private static final LogChannel log = Log.of("travel");

    private final Effects effects;
    private final java.util.function.Predicate<UUID> hidden;
    private volatile TravelLooks looks = TravelLooks.SERVERS;
    private volatile boolean complained;

    public TravelShow(Effects effects) {
        this(effects, who -> false);
    }

    /**
     * @param hidden who is vanished. Their teleports are silent and bare: a sound where they stood or
     *               landed, or particles around an empty spot, tells everybody nearby they are there
     */
    public TravelShow(Effects effects, java.util.function.Predicate<UUID> hidden) {
        this.effects = effects;
        this.hidden = hidden == null ? who -> false : hidden;
    }

    /** Where travellers' own choices come from. One source at a time; the last to register wins. */
    public void looks(TravelLooks source) {
        looks = source == null ? TravelLooks.SERVERS : source;
    }

    /** Lets go of {@code source} if it is still the one in use — for a plugin that is disabling. */
    public void release(TravelLooks source) {
        if (looks == source) {
            looks = TravelLooks.SERVERS;
        }
    }

    public void departed(UUID traveller, String world, double x, double y, double z) {
        if (hidden.test(traveller)) {
            return;
        }
        TravelLook look = lookFor(traveller);
        Effect server = effects.boundTo(Cues.TELEPORT_DEPART).orElse(Effect.silence());
        effects.playAt(world, x, y, z, look.depart() == null ? server : withSound(server, look.depart()));
    }

    public void arrived(UUID traveller, String world, double x, double y, double z) {
        if (hidden.test(traveller)) {
            return;
        }
        TravelLook look = lookFor(traveller);
        Effect server = effects.boundTo(Cues.TELEPORT).orElse(Effect.silence());
        effects.playAt(world, x, y, z, look.arrive() == null ? server : withSound(server, look.arrive()));
    }

    /** The particle drawn while they wait, or empty for none. */
    public Optional<String> waitParticle(UUID traveller) {
        if (!effects.isEnabled() || hidden.test(traveller)) {
            return Optional.empty();
        }
        String chosen = lookFor(traveller).waitParticle();
        if (chosen != null) {
            return chosen.isBlank() ? Optional.empty() : Optional.of(chosen.trim());
        }
        return effects.boundTo(Cues.TELEPORT_WAIT)
                .map(Effect::particles)
                .filter(burst -> burst != null && !burst.isNothing())
                .map(ParticleCue::particle);
    }

    public ParticleShape waitShape(UUID traveller) {
        ParticleShape chosen = lookFor(traveller).waitShape();
        return chosen == null ? ParticleShape.SPIRAL : chosen;
    }

    /** The server's effect with the traveller's sound in place of its own; its particles stay. */
    private static Effect withSound(Effect server, SoundCue sound) {
        return Effect.of(SoundSequence.of(sound), server == null ? ParticleSequence.nothing() : server.bursts());
    }

    private TravelLook lookFor(UUID traveller) {
        try {
            TravelLook look = looks.lookFor(traveller);
            return look == null ? TravelLook.SERVERS : look;
        } catch (RuntimeException broken) {
            // Somebody else's plugin. A teleport goes ahead with the server's effects rather than not at all.
            if (!complained) {
                complained = true;
                log.warn("The plugin choosing teleport effects failed ({}); the server's are used.", broken.toString());
            }
            return TravelLook.SERVERS;
        }
    }
}
