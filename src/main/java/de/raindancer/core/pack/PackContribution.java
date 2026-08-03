package de.raindancer.core.pack;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Assets one plugin wants on a player's screen.
 *
 * <h2>Why a plugin does not just send a pack</h2>
 * Because sending is the one thing it must not do. A player has one resource pack applied in any
 * meaningful sense, so two plugins each calling {@code setResourcePack} means the second one wins
 * and the first one's assets quietly disappear — no error, no log line, just a menu with missing
 * textures that nobody can explain. So a plugin says what it has and Core decides what is sent.
 *
 * @param owner       the plugin offering it, as a human writes it — {@code Claims}
 * @param name        what these assets are — {@code icons}
 * @param source      a zip or a folder on disk
 * @param priority    higher is applied later, so higher wins a conflict
 * @param description what it is for, shown when it collides with somebody else's
 */
public record PackContribution(String owner, String name, Path source, int priority,
                               String description) {

    public PackContribution {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("a contribution needs an owner");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a contribution needs a name");
        }
        if (source == null) {
            throw new IllegalArgumentException("a contribution needs a file or folder");
        }
        owner = owner.trim();
        name = name.trim();
        description = description == null ? "" : description.trim();
    }

    /** One plugin's assets, at the ordinary priority. */
    public static PackContribution of(String owner, String name, Path source) {
        return new PackContribution(owner, name, source, 0, "");
    }

    /**
     * What this is known by.
     *
     * <p>Lowercased so {@code Claims} and {@code claims} are one contribution rather than two — a
     * plugin that changes how it capitalises its own name between versions should not end up sending
     * its assets twice.
     */
    public String id() {
        return owner.toLowerCase(Locale.ROOT) + ":" + name.toLowerCase(Locale.ROOT);
    }

    /** The same, applied later or earlier. */
    public PackContribution priority(int priority) {
        return new PackContribution(owner, name, source, priority, description);
    }

    /** The same, with a line a human can read when it collides with something. */
    public PackContribution describedAs(String description) {
        return new PackContribution(owner, name, source, priority, description);
    }
}
