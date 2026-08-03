package de.raindancer.core.invsee;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Who is looking inside whose inventory, and what they may do there.
 *
 * <h2>Why the rules are the whole feature</h2>
 * Opening somebody else's inventory is three lines. Everything that makes it safe is around it, and
 * every rule here exists because the version without it has broken a real server:
 *
 * <ul>
 *   <li>Two moderators editing the same inventory at once <b>duplicates items</b>, every time. Only
 *       one editor is allowed; anybody else may watch.</li>
 *   <li>A window left open after its owner logs out writes changes to nobody, or loses them. The
 *       owner leaving closes every window onto them.</li>
 *   <li>An editor who logs out while holding the lock would keep it until a restart. Leaving
 *       releases it.</li>
 *   <li>Armour and the off-hand are protected unless somebody deliberately asks for them, because
 *       unequipping a player mid-fight by clicking one slot too far is not an edit anybody meant.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. The editor lock is taken atomically, so two moderators clicking at the same
 * instant cannot both be told yes.
 */
public final class InventoryViews {

    private static final LogChannel log = Log.of("invsee");

    /** Who each watcher is watching. */
    private final Map<UUID, UUID> watching = new ConcurrentHashMap<>();
    /** What each watcher may do. */
    private final Map<UUID, Access> access = new ConcurrentHashMap<>();
    /** Who holds the one editing lock on each inventory. */
    private final Map<UUID, UUID> editors = new ConcurrentHashMap<>();

    /** Told to shut a watcher's window — the server's job, and the only thing here that is. */
    private final Consumer<String> closeWindow;

    public InventoryViews(Consumer<String> closeWindow) {
        this.closeWindow = closeWindow;
    }

    // ---------------------------------------------------------------------------- opening

    /**
     * Starts watching somebody.
     *
     * @return whether it was allowed; false when somebody else is already editing, or when a player
     *         tried to watch themselves
     */
    public boolean open(UUID watcher, UUID owner, Access level) {
        if (watcher == null || owner == null || watcher.equals(owner)) {
            // Your own inventory is a key rather than a menu, and the two behave differently enough
            // that pretending otherwise causes items to vanish.
            return false;
        }
        Access wanted = level == null ? Access.READ_ONLY : level;

        // Whatever they had open before is let go first: one screen, one inventory. A window nobody
        // is looking at that still takes clicks is a window that will be clicked.
        //
        // Before taking the new lock rather than after, which is the order this had wrong. A
        // moderator re-opening the inventory they were already editing took the lock (it was
        // already theirs), then released it on the way past — leaving nobody holding it and a
        // second moderator free to edit the same inventory. That is the item-duplication case this
        // class exists to prevent, reintroduced by three lines in the wrong order.
        release(watcher);

        if (wanted.canEdit()) {
            // Taken atomically. Two moderators clicking at the same instant must not both be told
            // yes.
            UUID already = editors.putIfAbsent(owner, watcher);
            if (already != null && !already.equals(watcher)) {
                return false;
            }
        }
        watching.put(watcher, owner);
        access.put(watcher, wanted);
        return true;
    }

    // ---------------------------------------------------------------------------- asking

    /** Whose inventory somebody has open. */
    public Optional<UUID> watching(UUID watcher) {
        return watcher == null ? Optional.empty() : Optional.ofNullable(watching.get(watcher));
    }

    /** Everybody looking at one inventory. */
    public Set<UUID> watchersOf(UUID owner) {
        if (owner == null) {
            return Set.of();
        }
        return watching.entrySet().stream()
                .filter(entry -> entry.getValue().equals(owner))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Who, if anybody, is editing one inventory — so a watcher can be told why they cannot. */
    public Optional<UUID> editorOf(UUID owner) {
        return owner == null ? Optional.empty() : Optional.ofNullable(editors.get(owner));
    }

    /** What one watcher is allowed to do. */
    public Optional<Access> accessOf(UUID watcher) {
        return watcher == null ? Optional.empty() : Optional.ofNullable(access.get(watcher));
    }

    /** Whether a watcher may change something in one part of the inventory. */
    public boolean mayChange(UUID watcher, Section section) {
        return accessOf(watcher).map(level -> level.mayChange(section)).orElse(false);
    }

    /** Whether a watcher may change a raw inventory slot — for a click handler. */
    public boolean mayChangeSlot(UUID watcher, int rawSlot) {
        return Slots.sectionOf(rawSlot).map(section -> mayChange(watcher, section)).orElse(false);
    }

    public int size() {
        return watching.size();
    }

    // ---------------------------------------------------------------------------- closing

    /** Stops one watcher watching. Answers whether they were. */
    public boolean close(UUID watcher) {
        if (watcher == null || !watching.containsKey(watcher)) {
            return false;
        }
        release(watcher);
        return true;
    }

    /**
     * The owner has gone: every window onto them is closed.
     *
     * <p>A window onto somebody who has logged out is a window whose changes are written to nobody.
     *
     * @return who was watching, so they can be told why their screen shut
     */
    public Set<UUID> ownerLeft(UUID owner) {
        Set<UUID> theirWatchers = watchersOf(owner);
        for (UUID watcher : theirWatchers) {
            release(watcher);
            closeWindow.accept(watcher.toString());
        }
        editors.remove(owner);
        return theirWatchers;
    }

    /**
     * A watcher has gone.
     *
     * <p>The important half is the lock: an editor who logs out still holding it would stop anybody
     * editing that inventory until the server restarted.
     */
    public void watcherLeft(UUID watcher) {
        release(watcher);
    }

    /** Closes everything — for a shutdown. Answers how many. */
    public int closeEverything() {
        List<UUID> everybody = List.copyOf(watching.keySet());
        everybody.forEach(watcher -> {
            release(watcher);
            closeWindow.accept(watcher.toString());
        });
        return everybody.size();
    }

    /** Lets go of whatever one watcher held. */
    private void release(UUID watcher) {
        UUID owner = watching.remove(watcher);
        access.remove(watcher);
        if (owner != null) {
            // Only if it is theirs. Removing blindly would let a watcher release somebody else's
            // lock by closing their own read-only window.
            editors.remove(owner, watcher);
        }
    }
}
