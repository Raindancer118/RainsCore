package de.raindancer.core.ui.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Several sounds that together make one noise, written the way a server owner would type it.
 *
 * <h2>Why one sound per cue was not enough</h2>
 * A single {@link SoundCue} is right for a click, a page turn, a refusal. It is not enough for the things
 * people actually tune: a cannon, an explosion, a spell, a firework. Those are layered — a low boom under a
 * crack over a tail of debris — and the layering is the whole sound. Bound to one cue each, a server owner
 * has to pick which third of the noise they want.
 *
 * <p>This is not hypothetical: a live server's configuration had a sixteen-sound cannon, a nine-sound
 * elimination and a six-sound pair of boots, all written by hand in exactly the notation below. There was
 * nowhere in Core to put them, so every one of them would have come out as either one sound or none.
 *
 * <h2>The notation</h2>
 * Sounds separated by {@code ;}, each optionally decorated:
 *
 * <pre>
 *   ENTITY_GENERIC_EXPLODE                    plain, full volume, normal pitch
 *   ENTITY_GENERIC_EXPLODE@0.4                @ volume
 *   ENTITY_GENERIC_EXPLODE~1.6                ~ pitch
 *   ENTITY_GENERIC_EXPLODE&gt;200                &gt; start 200 ms late
 *   ENTITY_GENERIC_EXPLODE^3                  ^ three times over
 *   ENTITY_GENERIC_EXPLODE@0.4~1.6&gt;200^3      all four, in any order
 * </pre>
 *
 * <p>Bukkit's screaming-snake sound names are accepted as well as Minecraft's dotted keys, because that is
 * what is already written in the configuration files this has to read: {@code ENTITY_GENERIC_EXPLODE} and
 * {@code entity.generic.explode} name the same sound and both are spelled by people.
 *
 * <h2>Why parsing lives here and not where the file is read</h2>
 * So that it can be tested as a function, and so that every module reading a sound list from its own
 * configuration reads the same notation. A second parser in a second plugin is a second dialect, and the
 * server owner is the one who finds out.
 */
public record SoundSequence(List<Step> steps) {

    /** The longest sequence that will be accepted. Past this it is a mistake, not a sound. */
    public static final int MOST_STEPS = 32;

    /** How long a whole sequence may last. A sound still starting a minute later is not one sound. */
    public static final long LONGEST_DELAY_MILLIS = 30_000L;

    /** One sound in the sequence, and when it starts. */
    public record Step(SoundCue sound, long delayMillis) {

        public Step {
            delayMillis = Math.clamp(delayMillis, 0L, LONGEST_DELAY_MILLIS);
        }

        public static Step now(SoundCue sound) {
            return new Step(sound, 0L);
        }
    }

    public SoundSequence {
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (steps.size() > MOST_STEPS) {
            steps = steps.subList(0, MOST_STEPS);
        }
    }

    /** Nothing at all. */
    public static SoundSequence silence() {
        return new SoundSequence(List.of());
    }

    /** One sound, immediately — what every single-cue caller already meant. */
    public static SoundSequence of(SoundCue sound) {
        return sound == null ? silence() : new SoundSequence(List.of(Step.now(sound)));
    }

    public boolean isSilent() {
        return steps.isEmpty() || steps.stream().allMatch(step -> step.sound().isSilent());
    }

    /** The first sound, for a caller that can only play one. Null when there is nothing. */
    public SoundCue first() {
        return steps.isEmpty() ? null : steps.get(0).sound();
    }

    /** How long the whole thing takes, from the first sound to the last one starting. */
    public long lengthMillis() {
        return steps.stream().mapToLong(Step::delayMillis).max().orElse(0L);
    }

    /**
     * Reads the notation above. Never throws.
     *
     * <p>A step that cannot be read is dropped and the rest are kept, which is deliberate: a typo in the
     * eleventh sound of a cannon should cost the eleventh sound, not the cannon. Reporting is the caller's
     * job — {@link #problemsIn(String)} says what was dropped, so a settings screen can show it.
     */
    public static SoundSequence parse(String written) {
        if (written == null || written.isBlank()) {
            return silence();
        }
        List<Step> found = new ArrayList<>();
        for (String piece : written.split(";")) {
            Step step = parseStep(piece);
            if (step != null) {
                found.add(step);
            }
            if (found.size() >= MOST_STEPS) {
                break;
            }
        }
        return new SoundSequence(found);
    }

    /**
     * What {@link #parse} would have to drop, in words.
     *
     * <p>Separate from parsing rather than returned alongside it, because the overwhelmingly common caller
     * wants the sound and nothing else. Empty means the whole thing reads cleanly.
     */
    public static List<String> problemsIn(String written) {
        if (written == null || written.isBlank()) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        String[] pieces = written.split(";");
        for (String piece : pieces) {
            if (piece.isBlank()) {
                continue;
            }
            if (parseStep(piece) == null) {
                problems.add("'" + piece.strip() + "' is not a sound this can read");
            }
        }
        if (pieces.length > MOST_STEPS) {
            problems.add("only the first " + MOST_STEPS + " of " + pieces.length + " sounds are played");
        }
        return List.copyOf(problems);
    }

    /**
     * One decorated sound name, or null.
     *
     * <p>The decorations are read by scanning for their markers rather than by a regular expression, because
     * they may appear in any order and a pattern that allowed every order would be unreadable — and because
     * the repeat marker turns one written step into several played ones, which a matcher cannot express.
     */
    private static Step parseStep(String piece) {
        String text = piece == null ? "" : piece.strip();
        if (text.isEmpty()) {
            return null;
        }
        float volume = 1.0f;
        float pitch = 1.0f;
        long delay = 0L;

        int cut = text.length();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '@' || c == '~' || c == '>' || c == '^') {
                cut = Math.min(cut, i);
            }
        }
        String name = text.substring(0, cut).strip();
        if (name.isEmpty()) {
            return null;
        }

        // Each marker's value runs to the next marker or to the end.
        String decorations = text.substring(cut);
        int at = 0;
        while (at < decorations.length()) {
            char marker = decorations.charAt(at);
            int next = at + 1;
            while (next < decorations.length() && "@~>^".indexOf(decorations.charAt(next)) < 0) {
                next++;
            }
            String value = decorations.substring(at + 1, next).strip();
            try {
                switch (marker) {
                    case '@' -> volume = Float.parseFloat(value);
                    case '~' -> pitch = Float.parseFloat(value);
                    case '>' -> delay = Long.parseLong(value);
                    // The repeat marker is honoured by the caller that expands a written step into played
                    // ones; a single Step cannot repeat itself. See expand().
                    case '^' -> Integer.parseInt(value);
                    default -> { }
                }
            } catch (NumberFormatException notANumber) {
                // The whole step is refused rather than played with a wrong volume: a sound at ten times the
                // intended loudness is worse than one that is missing and reported.
                return null;
            }
            at = next;
        }
        return new Step(new SoundCue(normalise(name), volume, pitch), delay);
    }

    /**
     * How many times one written step is played, and how far apart.
     *
     * <p>{@code ^2} on an explosion is two explosions a beat apart, which is what makes it read as a double
     * boom rather than as one louder one. {@link #REPEAT_GAP_MILLIS} is not configurable because a repeat
     * with a settable gap is two steps with two delays, which the notation already expresses.
     */
    public static final long REPEAT_GAP_MILLIS = 80L;

    /**
     * The sequence with every {@code ^n} written out as {@code n} steps.
     *
     * <p>Called by {@link #parse}'s consumers rather than by {@code parse} itself, so that
     * {@link #problemsIn} and a settings screen can show what was written while playback sees what happens.
     */
    public static SoundSequence parseAndExpand(String written) {
        if (written == null || written.isBlank()) {
            return silence();
        }
        List<Step> found = new ArrayList<>();
        for (String piece : written.split(";")) {
            Step step = parseStep(piece);
            if (step == null) {
                continue;
            }
            int repeats = repeatsIn(piece);
            for (int i = 0; i < repeats && found.size() < MOST_STEPS; i++) {
                found.add(new Step(step.sound(), step.delayMillis() + i * REPEAT_GAP_MILLIS));
            }
            if (found.size() >= MOST_STEPS) {
                break;
            }
        }
        return new SoundSequence(found);
    }

    /** How many times that written step asks to be played. One when it does not say. */
    private static int repeatsIn(String piece) {
        int marker = piece.indexOf('^');
        if (marker < 0) {
            return 1;
        }
        int end = marker + 1;
        while (end < piece.length() && "@~>^".indexOf(piece.charAt(end)) < 0) {
            end++;
        }
        try {
            // Capped at eight: a sound asked for two hundred times is a typo, and playing it would be a
            // denial of service on whoever is wearing headphones.
            return Math.clamp(Integer.parseInt(piece.substring(marker + 1, end).strip()), 1, 8);
        } catch (NumberFormatException notANumber) {
            return 1;
        }
    }

    /**
     * A sound name as the protocol wants it.
     *
     * <p>{@code ENTITY_GENERIC_EXPLODE} becomes {@code entity.generic.explode}. A name that already has dots
     * is left alone, which is what lets a resource pack's own {@code custom.halt} through untouched.
     */
    public static String normalise(String name) {
        String trimmed = name.strip();
        if (trimmed.indexOf('.') >= 0 || trimmed.indexOf(':') >= 0) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return trimmed.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    /** The notation this sequence would be written as — for a settings screen showing what is bound. */
    public String written() {
        List<String> pieces = new ArrayList<>();
        for (Step step : steps) {
            StringBuilder piece = new StringBuilder(step.sound().key());
            if (step.sound().volume() != 1.0f) {
                piece.append('@').append(step.sound().volume());
            }
            if (step.sound().pitch() != 1.0f) {
                piece.append('~').append(step.sound().pitch());
            }
            if (step.delayMillis() > 0) {
                piece.append('>').append(step.delayMillis());
            }
            pieces.add(piece.toString());
        }
        return String.join("; ", pieces);
    }
}
