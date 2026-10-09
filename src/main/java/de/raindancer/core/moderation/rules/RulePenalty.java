package de.raindancer.core.moderation.rules;

import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.moderation.punishment.PunishmentKind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What breaking a rule costs, one rung of its ladder: {@code warn}, {@code kick}, {@code mute 1h},
 * {@code freeze 30m}, {@code ban 3d}, {@code ban} (for good).
 *
 * @param length how long a mute, freeze or ban lasts; null for good, and always null for a warning or kick
 */
public record RulePenalty(PunishmentKind kind, Duration length) {

    public RulePenalty {
        if (kind == null) {
            throw new IllegalArgumentException("a penalty needs a kind");
        }
        if (!kind.isLasting()) {
            length = null;
        }
    }

    public boolean isPermanent() {
        return kind.isLasting() && length == null;
    }

    public static Optional<RulePenalty> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] words = text.strip().toLowerCase(Locale.ROOT).split("\\s+", 2);
        PunishmentKind kind = switch (words[0]) {
            case "warn", "warning" -> PunishmentKind.WARNING;
            case "kick" -> PunishmentKind.KICK;
            case "mute" -> PunishmentKind.MUTE;
            case "freeze" -> PunishmentKind.FREEZE;
            case "ban" -> PunishmentKind.BAN;
            default -> null;
        };
        if (kind == null) {
            return Optional.empty();
        }
        if (words.length == 1) {
            return Optional.of(new RulePenalty(kind, null));
        }
        if (!kind.isLasting()) {
            return Optional.empty();
        }
        if (Durations.isForEver(words[1])) {
            return Optional.of(new RulePenalty(kind, null));
        }
        return Durations.parse(words[1]).map(length -> new RulePenalty(kind, length));
    }

    /** A whole ladder, rungs separated by commas; empty when any rung cannot be read. Blank is no ladder. */
    public static Optional<List<RulePenalty>> ladder(String text) {
        if (text == null || text.isBlank()) {
            return Optional.of(List.of());
        }
        List<RulePenalty> rungs = new ArrayList<>();
        for (String part : text.split(",")) {
            Optional<RulePenalty> rung = parse(part);
            if (rung.isEmpty()) {
                return Optional.empty();
            }
            rungs.add(rung.get());
        }
        return Optional.of(List.copyOf(rungs));
    }

    /** The way {@link #parse} reads it back. */
    public String write() {
        String word = switch (kind) {
            case WARNING -> "warn";
            case KICK -> "kick";
            case MUTE -> "mute";
            case FREEZE -> "freeze";
            case BAN -> "ban";
        };
        return length == null ? word : word + " " + compact(length);
    }

    public static String write(List<RulePenalty> ladder) {
        return String.join(", ", ladder.stream().map(RulePenalty::write).toList());
    }

    /** "a warning", "muted for 1 hour", "banned for good" — for players reading the rules. */
    public String describe() {
        return switch (kind) {
            case WARNING -> "a warning";
            case KICK -> "a kick";
            default -> kind.past() + (length == null ? " for good" : " for " + Durations.describe(length));
        };
    }

    /** "1st: a warning · 2nd: muted for 1 hour · then: banned for good" — what everybody is told up front. */
    public static String describe(List<RulePenalty> ladder) {
        if (ladder == null || ladder.isEmpty()) {
            return "";
        }
        if (ladder.size() == 1) {
            return "every time: " + ladder.getFirst().describe();
        }
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < ladder.size(); index++) {
            String when = index == ladder.size() - 1 ? "then" : ordinal(index + 1);
            parts.add(when + ": " + ladder.get(index).describe());
        }
        return String.join(" · ", parts);
    }

    /** "1st", "2nd", "11th". */
    public static String ordinal(int n) {
        String suffix = n % 100 >= 11 && n % 100 <= 13 ? "th"
                : switch (n % 10) {
                    case 1 -> "st";
                    case 2 -> "nd";
                    case 3 -> "rd";
                    default -> "th";
                };
        return n + suffix;
    }

    private static String compact(Duration length) {
        long minutes = length.toMinutes();
        if (minutes % (7 * 24 * 60) == 0) {
            return minutes / (7 * 24 * 60) + "w";
        }
        if (minutes % (24 * 60) == 0) {
            return minutes / (24 * 60) + "d";
        }
        if (minutes % 60 == 0) {
            return minutes / 60 + "h";
        }
        return minutes + "m";
    }
}
