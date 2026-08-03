package de.raindancer.core.moderation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading and writing the lengths of time a moderator types.
 *
 * <h2>Why not {@link Duration#parse}</h2>
 * Because it wants {@code PT30M}, and nobody types that. A moderator types {@code 30m}, or
 * {@code 1d12h}, or {@code perm}. This reads what they type and writes it back the way they would
 * say it.
 */
public final class Durations {

    /** A number and a unit: 30m, 2h, 7d. */
    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([smhdw])");

    /** What somebody types when they mean it never ends. */
    private static final List<String> FOR_EVER = List.of("perm", "permanent", "forever", "never",
            "inf", "infinite");

    private Durations() {
    }

    /**
     * How long somebody meant.
     *
     * <p>Empty means <em>for ever</em> — which is also what nonsense gives, deliberately: a
     * moderation command should refuse a length it did not understand rather than guess, and the
     * caller distinguishes the two by asking {@link #isForEver} first.
     */
    public static Optional<Duration> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String cleaned = text.trim().toLowerCase(Locale.ROOT);
        if (FOR_EVER.contains(cleaned)) {
            return Optional.empty();
        }
        Matcher parts = PART.matcher(cleaned);
        Duration total = Duration.ZERO;
        int matched = 0;
        int consumed = 0;
        while (parts.find()) {
            matched++;
            consumed += parts.group(0).length();
            long amount = Long.parseLong(parts.group(1));
            total = total.plus(switch (parts.group(2)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                case "w" -> Duration.ofDays(amount * 7);
                default -> Duration.ZERO;
            });
        }
        // Every character has to have been part of something, so "soon" and "-5d" are refused
        // rather than quietly becoming five days.
        if (matched == 0 || consumed != cleaned.replace(" ", "").length() || total.isZero()) {
            return Optional.empty();
        }
        return Optional.of(total);
    }

    /** Whether this is one of the words meaning "for ever", as opposed to something unreadable. */
    public static boolean isForEver(String text) {
        return text != null && FOR_EVER.contains(text.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * A length of time, written the way somebody would say it.
     *
     * <p>Null is "for ever", which is what a permanent punishment carries.
     */
    public static String describe(Duration duration) {
        if (duration == null) {
            return "for ever";
        }
        if (duration.isNegative() || duration.isZero()) {
            return "no time at all";
        }
        List<String> parts = new ArrayList<>();
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        if (days > 0) {
            parts.add(plural(days, "day"));
        }
        if (hours > 0) {
            parts.add(plural(hours, "hour"));
        }
        if (minutes > 0) {
            parts.add(plural(minutes, "minute"));
        }
        // Seconds only when they are the whole of it: "1 day 3 seconds" is noise.
        if (seconds > 0 && parts.isEmpty()) {
            parts.add(plural(seconds, "second"));
        }
        return String.join(" ", parts);
    }

    private static String plural(long amount, String unit) {
        return amount + " " + unit + (amount == 1 ? "" : "s");
    }
}
