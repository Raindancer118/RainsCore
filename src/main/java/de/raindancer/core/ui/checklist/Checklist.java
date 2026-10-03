package de.raindancer.core.ui.checklist;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Everything that has to be right before something starts — a round, a hunt, a reset — each with a
 * green or red answer, a sentence saying why, and where possible a fix one click away.
 *
 * <pre>{@code
 * Checklist checks = Checklist.titled("Before the start")
 *         .check(Check.of("world", "The arena world exists", world != null)
 *                 .because(world != null ? "'arena' is loaded." : "'arena' is not loaded.")
 *                 .fixedBy("Create it", player -> worlds.create(player)))
 *         .check(Check.warning("practice", "A real run", !kit.isPractice())
 *                 .because("Practice kit is on: this run will not rank."))
 *         .and(currentMode.checks());          // a game mode's own checks, appended
 *
 * new ChecklistMenu(viewer, brand, parent, () -> buildChecks(), "Start the countdown", this::start).open();
 * ChecklistChat.tell(viewer, checks, core.buttons());   // the same, in chat
 * }</pre>
 *
 * <h2>Several game modes</h2>
 * A checklist is built fresh each time it is asked for, so it always says what is true now. A plugin
 * with several modes builds the shared checks and appends the active mode's with {@link #and}; each
 * check carries a {@link Check#group group} so the screen shows them under their own heading.
 *
 * <p>Immutable; safe to build anywhere. Running a fix happens on the clicking player's own thread.
 */
public final class Checklist {

    /** How much a failing check matters. */
    public enum Severity {
        /** Stops the start. */
        BLOCKER,
        /** Worth knowing; does not stop anything. */
        WARNING
    }

    /** What clicking a red check does, and what it is called. */
    public record Fix(String label, Consumer<Player> action, String permission) {

        public Fix {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(action, "action");
        }

        /** Whether this player may click it. No permission means anybody who can see the list. */
        public boolean allowedFor(Player player) {
            return permission == null || permission.isBlank() || (player != null && player.hasPermission(permission));
        }
    }

    /** One line of the list. */
    public record Check(String id, String group, String label, boolean ok, Severity severity, String detail,
                        Fix fix) {

        public Check {
            Objects.requireNonNull(id, "id");
            group = group == null ? "" : group;
            label = label == null ? id : label;
            severity = severity == null ? Severity.BLOCKER : severity;
            detail = detail == null ? "" : detail;
        }

        /** A check that stops the start when it fails. */
        public static Check of(String id, String label, boolean ok) {
            return new Check(id, "", label, ok, Severity.BLOCKER, "", null);
        }

        /** A check that is only a warning when it fails. */
        public static Check warning(String id, String label, boolean ok) {
            return new Check(id, "", label, ok, Severity.WARNING, "", null);
        }

        /** The sentence under it: what is true now, or what is wrong and what to do. */
        public Check because(String why) {
            return new Check(id, group, label, ok, severity, why, fix);
        }

        /** The heading it is shown under — "World", "Manhunt". */
        public Check in(String heading) {
            return new Check(id, heading, label, ok, severity, detail, fix);
        }

        /** What clicking it does when it is red. */
        public Check fixedBy(String fixLabel, Consumer<Player> action) {
            return new Check(id, group, label, ok, severity, detail, new Fix(fixLabel, action, null));
        }

        /** The same, only for players with this permission. */
        public Check fixedBy(String fixLabel, Consumer<Player> action, String permission) {
            return new Check(id, group, label, ok, severity, detail, new Fix(fixLabel, action, permission));
        }

        /** Whether this one stops the start. */
        public boolean blocks() {
            return !ok && severity == Severity.BLOCKER;
        }

        public Optional<Fix> fixIfAny() {
            return ok ? Optional.empty() : Optional.ofNullable(fix);
        }
    }

    private final String title;
    private final List<Check> checks;

    private Checklist(String title, List<Check> checks) {
        this.title = title == null ? "Checks" : title;
        this.checks = List.copyOf(checks);
    }

    /** An empty list with a title — "Before the start". */
    public static Checklist titled(String title) {
        return new Checklist(title, List.of());
    }

    /** The same list with one more check. A second check with the same id replaces the first. */
    public Checklist check(Check check) {
        if (check == null) {
            return this;
        }
        List<Check> more = new ArrayList<>();
        boolean replaced = false;
        for (Check existing : checks) {
            if (existing.id().equals(check.id())) {
                more.add(check);
                replaced = true;
            } else {
                more.add(existing);
            }
        }
        if (!replaced) {
            more.add(check);
        }
        return new Checklist(title, more);
    }

    /** This list with another's checks appended — a game mode's own, say. */
    public Checklist and(Checklist other) {
        Checklist merged = this;
        if (other != null) {
            for (Check check : other.checks) {
                merged = merged.check(check);
            }
        }
        return merged;
    }

    public String title() {
        return title;
    }

    public List<Check> checks() {
        return checks;
    }

    /** Whether nothing stops the start. Warnings do not. */
    public boolean ready() {
        return checks.stream().noneMatch(Check::blocks);
    }

    /** Every check that failed, blockers first. */
    public List<Check> problems() {
        List<Check> failing = new ArrayList<>(checks.stream().filter(check -> !check.ok()).toList());
        failing.sort((one, other) -> Boolean.compare(other.blocks(), one.blocks()));
        return failing;
    }

    /** The checks that stop the start. */
    public List<Check> blockers() {
        return checks.stream().filter(Check::blocks).toList();
    }

    /** The checks by heading, in the order the headings first appear. Checks with no heading come first. */
    public Map<String, List<Check>> byGroup() {
        Map<String, List<Check>> grouped = new LinkedHashMap<>();
        checks.stream().filter(check -> check.group().isEmpty())
                .forEach(check -> grouped.computeIfAbsent("", key -> new ArrayList<>()).add(check));
        for (Check check : checks) {
            if (!check.group().isEmpty()) {
                grouped.computeIfAbsent(check.group(), key -> new ArrayList<>()).add(check);
            }
        }
        return grouped;
    }

    public Optional<Check> byId(String id) {
        return checks.stream().filter(check -> check.id().equals(id)).findFirst();
    }

    /** "3 of 5 ready" — the summary line. */
    public String summary() {
        long fine = checks.stream().filter(Check::ok).count();
        long blocking = blockers().size();
        if (ready()) {
            long warnings = checks.stream().filter(check -> !check.ok()).count();
            return warnings == 0 ? "All " + checks.size() + " checks are fine."
                    : "Ready, with " + warnings + (warnings == 1 ? " warning." : " warnings.");
        }
        return fine + " of " + checks.size() + " fine; " + blocking
                + (blocking == 1 ? " thing stops" : " things stop") + " the start.";
    }
}
