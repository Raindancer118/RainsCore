package de.raindancer.core.moderation.punishment;

import de.raindancer.core.world.time.Times;

import java.time.Duration;
import java.util.Optional;

/**
 * Reading and writing the lengths of time a moderator types.
 *
 * <h2>Why this is now four lines</h2>
 * Because it used to be a hundred, and so did the one in every other plugin that took a length of
 * time. It understood {@code s m h d w} and nothing else — no {@code 2min}, no months, no {@code 2
 * Minutes} — and each plugin's copy understood a slightly different three of those.
 *
 * <p>It all lives in {@link Times} now. This stays as the name the moderation code already calls,
 * and because {@code Durations.parse} reads better there than {@code Times.parse} does.
 *
 * @see Times for what is actually understood — in particular that {@code m} is minutes and
 *      {@code M} is months, which is the distinction a ban command cannot afford to get wrong
 */
public final class Durations {

    private Durations() {
    }

    /** How long somebody meant; empty for "for ever" or for something unreadable. */
    public static Optional<Duration> parse(String text) {
        return Times.parse(text);
    }

    /** Whether they meant it to never end. Ask this before treating an empty parse as a refusal. */
    public static boolean isForEver(String text) {
        return Times.isForEver(text);
    }

    /** A length of time, the way somebody would say it. */
    public static String describe(Duration duration) {
        return Times.describe(duration);
    }
}
