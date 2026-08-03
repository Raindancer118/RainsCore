package de.raindancer.core.time;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading and writing the lengths of time people actually type.
 *
 * <h2>Why not {@link Duration#parse}</h2>
 * Because it wants {@code PT30M}, and nobody has ever typed that into a chat box. People type
 * {@code 2min}, {@code 2m}, {@code 90s}, {@code 1h30m}, {@code 2 weeks}, {@code perm} — and every
 * plugin that takes a length of time grew its own half-parser understanding three of those and
 * silently misreading the rest.
 *
 * <h2>The ambiguity, on purpose</h2>
 * {@code m} is <b>minutes</b>. Everybody who has ever typed {@code /mute someone 5m} meant five
 * minutes, and a parser that read it as months would be catastrophically, silently wrong. Months are
 * {@code mo}, {@code month}, {@code months}, or a <b>capital {@code M}</b> — the shorthand people
 * already know from cron and from every ban plugin. That one letter is the whole reason this class
 * is worth having rather than a regex in each plugin.
 *
 * <h2>Months and years are not fixed lengths</h2>
 * {@link #parse} has to answer with a {@link Duration}, so a month there is thirty days and a year is
 * three hundred and sixty-five: an approximation, which {@link #isApproximate} will admit to. When
 * the exact answer matters — a one-month ban should end on the same day next month, not "in thirty
 * days" — use {@link #after}, which does real calendar arithmetic.
 *
 * <h2>It would rather refuse than guess</h2>
 * Anything not wholly understood comes back empty. Reading {@code 1h} out of {@code "1h and a bit"}
 * and dropping the rest is how somebody ends up banned for a length nobody asked for.
 */
public final class Times {

    /**
     * A number and a unit.
     *
     * <p>Matched case-insensitively so "2 Minutes" and "2H" work, with the longest spellings first
     * so "Minutes" is not read as a bare "M" with "inutes" left over. Which of {@code m} and
     * {@code M} was actually typed is then decided in {@link #unitOf} on the captured text, because
     * that single letter is the difference between two minutes and two months.
     */
    private static final Pattern PART = Pattern.compile(
            "(\\d+)\\s*(mo(?:nths?|ns?)?|minutes?|mins?|seconds?|secs?|hours?|hrs?|days?"
                    + "|weeks?|wks?|years?|yrs?|M|m|s|h|d|w|y)?",
            Pattern.CASE_INSENSITIVE);

    /** What somebody types when they mean it never ends. */
    private static final List<String> FOR_EVER = List.of("perm", "permanent", "permanently",
            "forever", "for ever", "never", "inf", "infinite", "always");

    /** A month, when one has to be a number of days. Wrong by up to a day and a half, and says so. */
    private static final long DAYS_IN_A_MONTH = 30;

    private static final long DAYS_IN_A_YEAR = 365;

    /** Longer than any server will run. Past this, refuse rather than overflow. */
    private static final Duration TOO_LONG = Duration.ofDays(365L * 1000);

    private Times() {
    }

    // ---------------------------------------------------------------------------- reading

    /**
     * How long somebody meant.
     *
     * <p>Empty means it could not be read <em>or</em> that they said "for ever" — the two are told
     * apart with {@link #isForEver}, which a caller must ask first. Folding them together is how a
     * typo becomes a permanent ban.
     */
    public static Optional<Duration> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String cleaned = text.trim();
        if (isForEver(cleaned)) {
            return Optional.empty();
        }
        // Lowercased everywhere except the unit letters, so "2 Minutes" works while M stays distinct
        // from m. Doing it the other way round is what makes most parsers of this get months wrong.
        String normalised = cleaned.replace(',', ' ').replaceAll("\\s+", " ");

        Matcher matcher = PART.matcher(normalised);
        Duration total = Duration.ZERO;
        int consumedTo = 0;
        boolean any = false;

        while (matcher.find()) {
            if (matcher.start() != skipSpaces(normalised, consumedTo)) {
                // Something that is not a number-and-unit sits between the parts. Refused, rather
                // than quietly reading the bits that happen to look like a length.
                return Optional.empty();
            }
            Optional<Duration> part = partOf(matcher.group(1), matcher.group(2));
            if (part.isEmpty()) {
                return Optional.empty();
            }
            total = total.plus(part.get());
            if (total.compareTo(TOO_LONG) > 0) {
                return Optional.empty();
            }
            consumedTo = matcher.end();
            any = true;
        }

        if (!any || skipSpaces(normalised, consumedTo) != normalised.length()) {
            return Optional.empty();
        }
        return total.isZero() || total.isNegative() ? Optional.empty() : Optional.of(total);
    }

    /** One number and one unit. A missing unit is seconds, which is what a bare number means. */
    private static Optional<Duration> partOf(String number, String unit) {
        long amount;
        try {
            amount = Long.parseLong(number);
        } catch (NumberFormatException tooBig) {
            // A number too long for a long is not a length of time anybody meant.
            return Optional.empty();
        }
        if (amount < 0) {
            return Optional.empty();
        }
        String kind = unit == null ? "s" : unit;
        try {
            return Optional.of(switch (unitOf(kind)) {
                case SECONDS -> Duration.ofSeconds(amount);
                case MINUTES -> Duration.ofMinutes(amount);
                case HOURS -> Duration.ofHours(amount);
                case DAYS -> Duration.ofDays(amount);
                case WEEKS -> Duration.ofDays(Math.multiplyExact(amount, 7));
                case MONTHS -> Duration.ofDays(Math.multiplyExact(amount, DAYS_IN_A_MONTH));
                case YEARS -> Duration.ofDays(Math.multiplyExact(amount, DAYS_IN_A_YEAR));
            });
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
    }

    /** The units this understands. */
    private enum Unit { SECONDS, MINUTES, HOURS, DAYS, WEEKS, MONTHS, YEARS }

    /**
     * Which unit a suffix is.
     *
     * <p>The capital {@code M} is checked before anything is lowercased — it is the one place in
     * this class where case carries meaning, and losing it turns minutes into months.
     */
    private static Unit unitOf(String suffix) {
        if (suffix.equals("M")) {
            return Unit.MONTHS;
        }
        String lower = suffix.toLowerCase(Locale.ROOT);
        if (lower.startsWith("mo")) {
            return Unit.MONTHS;
        }
        return switch (lower) {
            case "m", "min", "mins", "minute", "minutes" -> Unit.MINUTES;
            case "h", "hr", "hrs", "hour", "hours" -> Unit.HOURS;
            case "d", "day", "days" -> Unit.DAYS;
            case "w", "wk", "wks", "week", "weeks" -> Unit.WEEKS;
            case "y", "yr", "yrs", "year", "years" -> Unit.YEARS;
            default -> Unit.SECONDS;
        };
    }

    private static int skipSpaces(String text, int from) {
        int at = from;
        while (at < text.length() && text.charAt(at) == ' ') {
            at++;
        }
        return at;
    }

    /** Whether somebody meant it to never end. */
    public static boolean isForEver(String text) {
        return text != null && FOR_EVER.contains(text.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether reading this as a {@link Duration} involved rounding a month or a year.
     *
     * <p>For a caller who needs the exact answer to know it has to use {@link #after} instead.
     */
    public static boolean isApproximate(String text) {
        if (text == null) {
            return false;
        }
        Matcher matcher = PART.matcher(text.trim());
        while (matcher.find()) {
            String unit = matcher.group(2);
            if (unit != null) {
                Unit kind = unitOf(unit);
                if (kind == Unit.MONTHS || kind == Unit.YEARS) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------- real dates

    /**
     * When something typed as a length of time would actually end.
     *
     * <p>The exact version. A month here is a calendar month, so a ban set on the 15th ends on the
     * 15th and one set on the 31st of January ends on the 28th of February rather than spilling into
     * March. That is what somebody means by "a month", and thirty days is not it.
     *
     * @return when it ends, or empty when the text could not be read or means for ever
     */
    public static Optional<Instant> after(Instant from, String text) {
        if (from == null || text == null || text.isBlank() || isForEver(text)) {
            return Optional.empty();
        }
        String normalised = text.trim().replace(',', ' ').replaceAll("\\s+", " ");
        Matcher matcher = PART.matcher(normalised);

        ZonedDateTime when = ZonedDateTime.ofInstant(from, ZoneOffset.UTC);
        int consumedTo = 0;
        boolean any = false;

        while (matcher.find()) {
            if (matcher.start() != skipSpaces(normalised, consumedTo)) {
                return Optional.empty();
            }
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException tooBig) {
                return Optional.empty();
            }
            String unit = matcher.group(2);
            try {
                when = switch (unitOf(unit == null ? "s" : unit)) {
                    case SECONDS -> when.plusSeconds(amount);
                    case MINUTES -> when.plusMinutes(amount);
                    case HOURS -> when.plusHours(amount);
                    case DAYS -> when.plusDays(amount);
                    case WEEKS -> when.plusWeeks(amount);
                    // plusMonths does the day-of-month clamping for us, which is the whole reason
                    // this method exists rather than multiplying by thirty.
                    case MONTHS -> when.plusMonths(amount);
                    case YEARS -> when.plusYears(amount);
                };
            } catch (RuntimeException outOfRange) {
                return Optional.empty();
            }
            consumedTo = matcher.end();
            any = true;
        }
        if (!any || skipSpaces(normalised, consumedTo) != normalised.length()) {
            return Optional.empty();
        }
        return when.toInstant().isAfter(from) ? Optional.of(when.toInstant()) : Optional.empty();
    }

    // ---------------------------------------------------------------------------- writing

    /**
     * A length of time, the way somebody would say it.
     *
     * <p>Two parts at most. "1 year, 1 month, 4 days, 3 hours, 7 minutes" is a wall of text nobody
     * reads past the second comma of, so it stops there.
     *
     * @param duration null means for ever, which is a real answer rather than a missing one
     */
    public static String describe(Duration duration) {
        if (duration == null) {
            return "for ever";
        }
        if (duration.isZero() || duration.isNegative()) {
            return "no time at all";
        }

        List<String> parts = new ArrayList<>();
        long seconds = duration.getSeconds();

        long years = seconds / (DAYS_IN_A_YEAR * 86_400);
        seconds %= DAYS_IN_A_YEAR * 86_400;
        long months = seconds / (DAYS_IN_A_MONTH * 86_400);
        seconds %= DAYS_IN_A_MONTH * 86_400;
        long days = seconds / 86_400;
        seconds %= 86_400;
        long hours = seconds / 3_600;
        seconds %= 3_600;
        long minutes = seconds / 60;
        seconds %= 60;

        add(parts, years, "year");
        add(parts, months, "month");
        add(parts, days, "day");
        add(parts, hours, "hour");
        add(parts, minutes, "minute");
        add(parts, seconds, "second");

        return String.join(", ", parts.subList(0, Math.min(2, parts.size())));
    }

    private static void add(List<String> parts, long amount, String name) {
        if (amount > 0) {
            parts.add(amount + " " + name + (amount == 1 ? "" : "s"));
        }
    }

    /**
     * The same, short enough for a scoreboard or a button.
     *
     * <p>What this writes, {@link #parse} reads — so a length shown in a menu can be typed straight
     * back into a command, which is the sort of thing that is obvious only when it does not work.
     */
    public static String brief(Duration duration) {
        if (duration == null) {
            return "∞";
        }
        if (duration.isZero() || duration.isNegative()) {
            return "0s";
        }
        long seconds = duration.getSeconds();
        List<String> parts = new ArrayList<>();
        long days = seconds / 86_400;
        seconds %= 86_400;
        long hours = seconds / 3_600;
        seconds %= 3_600;
        long minutes = seconds / 60;
        seconds %= 60;

        if (days > 0) {
            parts.add(days + "d");
        }
        if (hours > 0) {
            parts.add(hours + "h");
        }
        if (minutes > 0) {
            parts.add(minutes + "m");
        }
        if (seconds > 0) {
            parts.add(seconds + "s");
        }
        return String.join(" ", parts);
    }
}
