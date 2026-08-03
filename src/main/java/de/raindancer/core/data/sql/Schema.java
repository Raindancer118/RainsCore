package de.raindancer.core.data.sql;

import java.util.List;

/**
 * What a database should look like, as an ordered list of steps that get it there.
 *
 * <h2>Why a list and not a file of CREATE TABLE statements</h2>
 * Because the second version of any schema is the hard one. A file describing the shape you want
 * tells a fresh database everything and an existing one nothing: somebody has to work out which of
 * those tables already exist, which columns were added since, and in what order — and that somebody
 * ends up being a human reading a stack trace on a live server.
 *
 * <p>A list makes the answer arithmetic instead. Every step is a change, the steps never change
 * once shipped, and the database remembers how many of them it has run. A fresh database runs all of
 * them; one from last month's version runs the tail. Nothing has to be detected.
 *
 * <h2>The one rule</h2>
 * <b>A step that has shipped is never edited or removed — only appended to.</b> Editing step three
 * changes nothing on any database that already ran it, so the two diverge silently: the tests pass
 * against the new shape and the server runs the old one. Removing a step is worse, because it
 * renumbers every step after it and a database will then re-run work it has already done.
 *
 * <p>Fixing a mistake in a shipped step therefore means a <em>new</em> step that corrects it. That is
 * not a workaround; it is the same discipline every migration tool arrives at.
 */
public record Schema(List<String> steps) {

    public Schema {
        steps = List.copyOf(steps);
    }

    /** @param steps one change each, in the order they were added, never reordered afterwards */
    public static Schema of(String... steps) {
        return new Schema(List.of(steps));
    }

    public static Schema none() {
        return new Schema(List.of());
    }

    /** How many steps a fully applied database will have run — its version. */
    public int size() {
        return steps.size();
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** The steps a database on {@code version} has not run yet, with the version each one reaches. */
    public List<Step> after(int version) {
        int from = Math.max(0, version);
        if (from >= steps.size()) {
            return List.of();
        }
        return java.util.stream.IntStream.range(from, steps.size())
                .mapToObj(at -> new Step(at + 1, steps.get(at)))
                .toList();
    }

    /**
     * One change, and the version reached by making it.
     *
     * @param version what the database's version becomes once this has run — one-based, so that a
     *                fresh database's zero means "nothing has been done" rather than "step zero"
     */
    public record Step(int version, String sql) {
    }
}
