package de.raindancer.core.ui.effect;

/**
 * What one named cue actually does: a sound, some particles, or both.
 *
 * <p>Either half may be absent, and both being absent is a deliberate silence rather than a mistake
 * — see {@link #silence()}, which is how a server owner turns off a cue that every plugin on the
 * server is asking for.
 *
 * @param sound     the sound, or null
 * @param particles the particles, or null
 */
public record Effect(SoundCue sound, ParticleCue particles) {

    /** Just a sound. */
    public static Effect of(SoundCue sound) {
        return new Effect(sound, null);
    }

    /** Just particles. */
    public static Effect of(ParticleCue particles) {
        return new Effect(null, particles);
    }

    /**
     * Nothing at all.
     *
     * <p>Bound over a cue to switch it off everywhere at once. Better than removing it: a cue that
     * is missing is a warning in the log every time a plugin asks for it, and a cue that is silent
     * is a decision.
     */
    public static Effect silence() {
        return new Effect(null, null);
    }

    public boolean isSilent() {
        return (sound == null || sound.isSilent()) && (particles == null || particles.isNothing());
    }
}
