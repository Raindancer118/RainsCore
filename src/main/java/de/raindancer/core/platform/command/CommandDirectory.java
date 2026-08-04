package de.raindancer.core.platform.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Every command on this server, as reported by whoever owns it.
 *
 * <h2>Why this is Core's and not one plugin's</h2>
 * Because the question a player is asking — "what can I type?" — spans every plugin, and no plugin
 * can answer it. Moderation knows six commands, warps knows four, and a book built from either is
 * confidently wrong about the other twenty. Core is the only thing all of them already talk to, so
 * it is the only place the list can be complete.
 *
 * <p>Reported rather than discovered. Bukkit's command map does hold every registered name, but what
 * it holds is a name and Brigadier's own one-line description — no options, no permission a book can
 * filter on, and every vanilla and third-party command mixed in. A directory of {@code /minecraft:tp}
 * beside {@code /warp} is a directory nobody uses twice. So a plugin says what it offers, in the
 * words it wants read, and what it does not say is not in the book.
 *
 * <h2>Late is fine, missing is not</h2>
 * A module reports as it enables, which is after its commands were registered at bootstrap and long
 * before anybody opens the book. Nothing here is read until then, so ordering does not matter — and
 * a module that never reports is simply absent, which is why {@link #declared} exists for a test to
 * hold a module to its own list.
 */
public final class CommandDirectory {

    /** Keyed by command name, so a plugin re-reporting on reload replaces rather than doubles. */
    private final Map<String, CommandNote> notes = new LinkedHashMap<>();

    /**
     * Adds a command to the directory, or replaces what was there under that name.
     *
     * <p>Last one in wins, deliberately: the alternative is a module reloading and appearing twice,
     * which is the failure a reader would actually notice.
     */
    public synchronized CommandDirectory declare(CommandNote note) {
        if (note != null) {
            notes.put(note.command().toLowerCase(Locale.ROOT), note);
        }
        return this;
    }

    /** Several at once, which is what a module's own list looks like. */
    public synchronized CommandDirectory declareAll(Iterable<CommandNote> many) {
        if (many != null) {
            many.forEach(this::declare);
        }
        return this;
    }

    /** Forgets everything one plugin reported. For a module that is being disabled. */
    public synchronized int forget(String plugin) {
        if (plugin == null) {
            return 0;
        }
        int before = notes.size();
        notes.values().removeIf(note -> note.plugin().equalsIgnoreCase(plugin));
        return before - notes.size();
    }

    /** Everything, sorted by plugin and then by name. */
    public synchronized List<CommandNote> all() {
        List<CommandNote> found = new ArrayList<>(notes.values());
        found.sort(CommandNote::compareTo);
        return List.copyOf(found);
    }

    /** Whether anything has reported at all — the one state a book cannot be built from. */
    public synchronized boolean isEmpty() {
        return notes.isEmpty();
    }

    /** What one plugin reported, in order. */
    public synchronized List<CommandNote> declared(String plugin) {
        return all().stream()
                .filter(note -> note.plugin().equalsIgnoreCase(plugin))
                .toList();
    }

    /**
     * Everything one reader may use.
     *
     * <p>A note with no permission is everybody's. A note with one is shown only to somebody who has
     * it — not greyed, absent: a directory that lists {@code /ban} to every player is a directory
     * that teaches every player the staff commands by name.
     */
    public List<CommandNote> visibleTo(Predicate<String> mayUse) {
        return all().stream()
                .filter(note -> note.permission() == null || mayUse.test(note.permission()))
                .toList();
    }

    /** The plugins that have reported anything, in the order the book puts them. */
    public List<String> plugins() {
        List<String> found = new ArrayList<>();
        for (CommandNote note : all()) {
            if (!found.contains(note.plugin())) {
                found.add(note.plugin());
            }
        }
        return List.copyOf(found);
    }
}
