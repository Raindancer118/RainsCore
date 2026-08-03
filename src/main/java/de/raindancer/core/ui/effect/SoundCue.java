package de.raindancer.core.ui.effect;

/**
 * One sound, as the protocol wants it.
 *
 * <p>A key rather than Bukkit's {@code Sound} enum, so a resource pack's own sound works exactly like
 * a vanilla one and a name that a future version renames is a warning rather than a compile error.
 *
 * @param key    the sound's name — {@code block.note_block.bell}, or one from a resource pack
 * @param volume how loud, and past 1.0 also how far away it can be heard
 * @param pitch  0.5 to 2.0; anything else is silently ignored by the client
 */
public record SoundCue(String key, float volume, float pitch) {

    public SoundCue {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a sound needs a name");
        }
        key = key.trim();
        // Clamped rather than rejected: an out-of-range pitch is not refused by the client, it is
        // ignored, which reads as "the sound is broken" and sends somebody hunting in the wrong place.
        volume = Math.clamp(volume, 0f, 10f);
        pitch = Math.clamp(pitch, 0.5f, 2.0f);
    }

    /** The same sound, at a different pitch — for the two-note up and down every menu wants. */
    public SoundCue atPitch(float pitch) {
        return new SoundCue(key, volume, pitch);
    }

    public boolean isSilent() {
        return volume <= 0f;
    }
}
