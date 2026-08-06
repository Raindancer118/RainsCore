package de.raindancer.core.ui.effect;

/**
 * What one named cue actually does: a sound, some particles, or both.
 *
 * <p>Either half may be absent, and both being absent is a deliberate silence rather than a mistake
 * — see {@link #silence()}, which is how a server owner turns off a cue that every plugin on the
 * server is asking for.
 *
 * <h2>Why the sound half is a sequence</h2>
 * A click is one sound; a cannon is sixteen. See {@link SoundSequence} — the layering <em>is</em> the noise
 * for anything bigger than a button, and a cue that could hold only one sound made every server owner
 * choose which third of their explosion to keep. {@link #sound()} still answers with the first one, so
 * everything written against the single-sound shape keeps working and keeps meaning the same thing.
 *
 * @param sounds the sound or sounds, never null — {@link SoundSequence#silence()} for none
 * @param bursts the particles, never null — {@link ParticleSequence#nothing()} for none
 */
public record Effect(SoundSequence sounds, ParticleSequence bursts) {

    public Effect {
        sounds = sounds == null ? SoundSequence.silence() : sounds;
        bursts = bursts == null ? ParticleSequence.nothing() : bursts;
    }

    /**
     * The first burst, for a caller that draws one.
     *
     * <p>Null when there is nothing, which is exactly what the field used to be — see {@link #sound()} for
     * the same argument on the sound half.
     */
    public ParticleCue particles() {
        return bursts.first();
    }

    /** Just a sound. */
    public static Effect of(SoundCue sound) {
        return new Effect(SoundSequence.of(sound), ParticleSequence.nothing());
    }

    /** Several sounds that make one noise. */
    public static Effect of(SoundSequence sounds) {
        return new Effect(sounds, ParticleSequence.nothing());
    }

    /** Layered sounds and layered particles, which is what anything bigger than a button actually is. */
    public static Effect of(SoundSequence sounds, ParticleSequence bursts) {
        return new Effect(sounds, bursts);
    }

    /**
     * The two-argument shape everything was written against.
     *
     * <p>Kept because {@code new Effect(sound, particles)} appears in every module and in Core's own
     * defaults, and because one sound plus some particles is still the ordinary case.
     */
    public Effect(SoundCue sound, ParticleCue particles) {
        this(SoundSequence.of(sound), ParticleSequence.of(particles));
    }

    /**
     * The first sound, for a caller that plays one.
     *
     * <p>Null when the cue is silent, which is exactly what the field used to be.
     */
    public SoundCue sound() {
        return sounds.first();
    }

    /** Just particles. */
    public static Effect of(ParticleCue particles) {
        // Spelled out rather than passing null: with both a SoundCue and a SoundSequence overload, a bare
        // null names neither constructor.
        return new Effect(SoundSequence.silence(), ParticleSequence.of(particles));
    }

    /**
     * Nothing at all.
     *
     * <p>Bound over a cue to switch it off everywhere at once. Better than removing it: a cue that
     * is missing is a warning in the log every time a plugin asks for it, and a cue that is silent
     * is a decision.
     */
    public static Effect silence() {
        return new Effect(SoundSequence.silence(), ParticleSequence.nothing());
    }

    public boolean isSilent() {
        return sounds.isSilent() && bursts.isNothing();
    }
}
