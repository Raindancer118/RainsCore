package de.raindancer.core.ui.effect;

import java.util.ArrayList;
import java.util.List;

/**
 * Several particle bursts that together make one visible thing, in the same notation
 * {@link SoundSequence} uses for sounds.
 *
 * <h2>Why layers</h2>
 * The same reason sounds layer. Smoke is smoke; a smoke bomb is a hundred and forty large smoke particles
 * at wide spread <em>plus</em> sixty campfire wisps, and taking either half away leaves something that
 * reads as a glitch. A server owner tuning that has already written both halves on one line — this is what
 * reads it.
 *
 * <h2>The notation</h2>
 * Bursts separated by {@code ;}, each optionally decorated:
 *
 * <pre>
 *   LARGE_SMOKE            plain, one particle, no spread
 *   LARGE_SMOKE@140        &#64; how many
 *   LARGE_SMOKE~2.2        ~ how far they scatter, in blocks, on all three axes
 *   LARGE_SMOKE#ff2020     # a colour, for the particles that take one (DUST, and its variants)
 *   LARGE_SMOKE@140~2.2    together
 * </pre>
 *
 * <p>The colour is packed into {@link ParticleCue#speed()}, which is the field the protocol reuses as a
 * particle's "extra" value — that is Minecraft's own arrangement rather than a trick here, and the sink is
 * where it is unpacked. A colour on a particle that has no use for one is harmless and ignored.
 */
public record ParticleSequence(List<ParticleCue> bursts) {

    /** The most layers one visible effect may have. Past this it is a mistake, not an effect. */
    public static final int MOST_BURSTS = 8;

    public ParticleSequence {
        bursts = bursts == null ? List.of() : List.copyOf(bursts);
        if (bursts.size() > MOST_BURSTS) {
            bursts = bursts.subList(0, MOST_BURSTS);
        }
    }

    public static ParticleSequence nothing() {
        return new ParticleSequence(List.of());
    }

    public static ParticleSequence of(ParticleCue burst) {
        return burst == null ? nothing() : new ParticleSequence(List.of(burst));
    }

    public boolean isNothing() {
        return bursts.isEmpty() || bursts.stream().allMatch(ParticleCue::isNothing);
    }

    /** The first burst, for a caller that draws one. Null when there is nothing. */
    public ParticleCue first() {
        return bursts.isEmpty() ? null : bursts.get(0);
    }

    /**
     * Reads the notation above. Never throws.
     *
     * <p>A layer that cannot be read is dropped and the rest kept — for the same reason
     * {@link SoundSequence#parse} does it: a typo in the second half of a smoke bomb should cost the second
     * half, not the bomb. {@link #problemsIn(String)} says what was dropped.
     */
    public static ParticleSequence parse(String written) {
        if (written == null || written.isBlank()) {
            return nothing();
        }
        List<ParticleCue> found = new ArrayList<>();
        for (String piece : written.split(";")) {
            ParticleCue burst = parseBurst(piece);
            if (burst != null) {
                found.add(burst);
            }
            if (found.size() >= MOST_BURSTS) {
                break;
            }
        }
        return new ParticleSequence(found);
    }

    /** What {@link #parse} would have to drop, in words. Empty means it reads cleanly. */
    public static List<String> problemsIn(String written) {
        if (written == null || written.isBlank()) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        for (String piece : written.split(";")) {
            if (!piece.isBlank() && parseBurst(piece) == null) {
                problems.add("'" + piece.strip() + "' is not a particle effect this can read");
            }
        }
        return List.copyOf(problems);
    }

    /** One decorated particle name, or null. */
    private static ParticleCue parseBurst(String piece) {
        String text = piece == null ? "" : piece.strip();
        if (text.isEmpty()) {
            return null;
        }
        int count = 1;
        double spread = 0;
        double extra = 0;

        int cut = text.length();
        for (int i = 0; i < text.length(); i++) {
            if ("@~#".indexOf(text.charAt(i)) >= 0) {
                cut = Math.min(cut, i);
            }
        }
        String name = text.substring(0, cut).strip();
        if (name.isEmpty()) {
            return null;
        }

        String decorations = text.substring(cut);
        int at = 0;
        while (at < decorations.length()) {
            char marker = decorations.charAt(at);
            int next = at + 1;
            while (next < decorations.length() && "@~#".indexOf(decorations.charAt(next)) < 0) {
                next++;
            }
            String value = decorations.substring(at + 1, next).strip();
            try {
                switch (marker) {
                    case '@' -> count = Integer.parseInt(value);
                    case '~' -> spread = Double.parseDouble(value);
                    // Base 16, and packed into the cue's extra value. A colour written with a leading hash
                    // is how everybody writes one, so the hash is the marker rather than part of the number.
                    case '#' -> extra = Integer.parseInt(value, 16);
                    default -> { }
                }
            } catch (NumberFormatException notANumber) {
                return null;
            }
            at = next;
        }
        return new ParticleCue(name, count, spread, spread, spread, extra);
    }
}
