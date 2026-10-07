package de.raindancer.core.world.teleport;

import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.core.ui.effect.SoundCue;

/**
 * How one traveller's teleports sound and look, where they differ from the server's cues.
 *
 * <p>Every field is nullable, and null means "whatever the server's cue says" — so a player who chose
 * only an arrival sound keeps the owner's departure. Choosing nothing at all is its own value:
 * {@link #NOTHING_HEARD} for a sound, an empty string for the waiting particle.
 *
 * @param depart       heard where they stood, as the warm-up starts or the instant teleport happens
 * @param arrive       heard where they land; the server's arrival particles stay
 * @param waitParticle drawn around them while they stand still, a Bukkit particle name
 * @param waitShape    the shape it is drawn in
 * @param tick         heard by them alone at each second of the countdown
 */
public record TravelLook(SoundCue depart, SoundCue arrive, String waitParticle, ParticleShape waitShape,
                         SoundCue tick) {

    /** Everything as the server has it. */
    public static final TravelLook SERVERS = new TravelLook(null, null, null, null, null);

    /** The shape plugins built against 1.54.0 were compiled against: the countdown stays the server's. */
    public TravelLook(SoundCue depart, SoundCue arrive, String waitParticle, ParticleShape waitShape) {
        this(depart, arrive, waitParticle, waitShape, null);
    }

    /** A sound that is deliberately nothing. */
    public static final SoundCue NOTHING_HEARD = new SoundCue("silence", 0f, 1f);
}
