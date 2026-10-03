package de.raindancer.core.ui.effect;

import java.util.Locale;

/**
 * A puff of particles.
 *
 * <p>The particle is named rather than typed for the same reason a sound is: a name that a future
 * version drops is a warning at runtime rather than a plugin that will not compile against the new
 * server.
 *
 * @param particle the particle's name, as the server spells it — {@code HAPPY_VILLAGER}
 * @param count    how many
 * @param spreadX  how far they scatter, in blocks
 * @param spreadY  the same, vertically
 * @param spreadZ  the same again
 * @param speed    how fast they move; for several particles this is their "extra" value instead
 * @param colour   {@code 0xRRGGBB} for a particle that takes a colour ({@code DUST}, {@code ENTITY_EFFECT}),
 *                 or null for none
 */
public record ParticleCue(String particle, int count, double spreadX, double spreadY,
                          double spreadZ, double speed, Integer colour) {

    public ParticleCue {
        if (particle == null || particle.isBlank()) {
            throw new IllegalArgumentException("a particle effect needs a particle");
        }
        particle = particle.trim().toUpperCase(Locale.ROOT);
        // Capped rather than trusted. A thousand particles is a plugin's typo and a client's
        // stutter, and the player it happens to has no way of telling which plugin did it.
        count = Math.clamp(count, 0, 500);
        speed = Math.max(0, speed);
        colour = colour == null ? null : colour & 0xFFFFFF;
    }

    /** A burst with no colour — the shape every cue had before colours. */
    public ParticleCue(String particle, int count, double spreadX, double spreadY, double spreadZ,
                       double speed) {
        this(particle, count, spreadX, spreadY, spreadZ, speed, null);
    }

    /** A simple burst at one spot. */
    public static ParticleCue of(String particle, int count) {
        return new ParticleCue(particle, count, 0, 0, 0, 0);
    }

    public boolean isNothing() {
        return count <= 0;
    }
}
