package de.raindancer.core.moderation.audit;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What somebody is looking for in the journal.
 *
 * <h2>Why one object rather than a method per question</h2>
 * Because the questions combine. "What did this moderator do" and "what happened in invsee" are each
 * one method, but "what did this moderator do in invsee last week" is the question actually asked
 * when something has gone wrong — and a class with a method per combination has eight of them and
 * still not the one you want.
 *
 * <p>Every part is optional and they narrow together. Nothing set means "everything, newest first",
 * which is the right default for a screen somebody just opened.
 *
 * @param actor    who did it
 * @param subject  who it was done to
 * @param feature  which part of the server
 * @param action   what was done
 * @param since    nothing older than this
 * @param until    nothing newer than this
 * @param limit    how many at most, newest first
 */
public record AuditSearch(UUID actor, UUID subject, String feature, String action,
                          Instant since, Instant until, int limit) {

    /**
     * How many entries come back when nobody said.
     *
     * <p>Capped rather than unbounded because the honest answer to "show me the audit log" on a
     * server that has been running for a year is millions of rows, and a caller that asked casually
     * would hold all of them in memory to show twenty.
     */
    public static final int DEFAULT_LIMIT = 100;

    /** The most anybody gets in one go, however large a number they ask for. */
    public static final int MAX_LIMIT = 10_000;

    public AuditSearch {
        feature = blankToNull(feature);
        action = blankToNull(action);
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Everything, newest first. */
    public static AuditSearch everything() {
        return new AuditSearch(null, null, null, null, null, null, DEFAULT_LIMIT);
    }

    /** What one moderator has done. */
    public static AuditSearch by(UUID actor) {
        return everything().withActor(actor);
    }

    /** What has been done to one player. */
    public static AuditSearch to(UUID subject) {
        return everything().withSubject(subject);
    }

    /** What has happened in one part of the server. */
    public static AuditSearch in(String feature) {
        return everything().withFeature(feature);
    }

    public AuditSearch withActor(UUID who) {
        return new AuditSearch(who, subject, feature, action, since, until, limit);
    }

    public AuditSearch withSubject(UUID who) {
        return new AuditSearch(actor, who, feature, action, since, until, limit);
    }

    public AuditSearch withFeature(String what) {
        return new AuditSearch(actor, subject, what, action, since, until, limit);
    }

    public AuditSearch withAction(String what) {
        return new AuditSearch(actor, subject, feature, what, since, until, limit);
    }

    public AuditSearch since(Instant when) {
        return new AuditSearch(actor, subject, feature, action, when, until, limit);
    }

    public AuditSearch until(Instant when) {
        return new AuditSearch(actor, subject, feature, action, since, when, limit);
    }

    public AuditSearch limit(int howMany) {
        return new AuditSearch(actor, subject, feature, action, since, until, howMany);
    }

    public Optional<UUID> actorId() {
        return Optional.ofNullable(actor);
    }

    public Optional<UUID> subjectId() {
        return Optional.ofNullable(subject);
    }

    /** Whether this narrows anything at all. */
    public boolean isEverything() {
        return actor == null && subject == null && feature == null && action == null
                && since == null && until == null;
    }
}
