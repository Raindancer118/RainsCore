package de.raindancer.core.invsee;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import de.raindancer.core.util.Scheduling;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Looking inside somebody's inventory — online or not.
 *
 * <h2>What this class is for</h2>
 * The three things that make an invsee safe live in three separate classes, each testable without a
 * server: who may edit ({@link InventoryViews}), what happens to an offline edit when its owner logs
 * back in ({@link OfflineEdits}), and where the items come from ({@link InventorySource}). This is
 * the one place that knows all three, and the only place that has to decide which kind of player is
 * being looked at.
 *
 * <h2>Why the offline half exists at all</h2>
 * Because the players a moderator most needs to look at are exactly the ones who have logged out.
 * "Only while they are online" sounds like a small gap and is the half that matters — somebody
 * reported after they left, a duplication traced through the people who received the items,
 * belongings to give back to a returning player.
 *
 * <p>Nobody is ever stopped from joining to make that work. If somebody logs in while their saved
 * inventory is open in a window, the window shuts and the edit is dropped unwritten — see
 * {@link #somebodyJoined}.
 */
public final class Inventories {

    private static final LogChannel log = Log.of("invsee");

    /** What happened when somebody tried to look. */
    public enum Outcome {

        OPENED("Opened."),
        YOURSELF("Your own inventory is a key rather than a menu."),
        BEING_EDITED("Somebody else is editing that inventory."),
        NEVER_SEEN("This server has never seen that player."),
        UNREADABLE("Their inventory could not be read — see the log."),
        NOT_ALLOWED("You may not do that.");

        private final String saying;

        Outcome(String saying) {
            this.saying = saying;
        }

        /** What to tell the moderator. */
        public String saying() {
            return saying;
        }

        public boolean opened() {
            return this == OPENED;
        }
    }

    private final Plugin plugin;
    private final InventoryViews views;
    private final OfflineEdits offlineEdits;
    private final InventorySource online;
    private final PlayerDataInventorySource saved;
    /** Whether somebody is on the server — a seam, so the decision is not a static call. */
    private final Predicate<UUID> isOnline;

    public Inventories(Plugin plugin, InventoryViews views, OfflineEdits offlineEdits,
                       Path playerData) {
        this(plugin, views, offlineEdits, new OnlineInventorySource(),
                new PlayerDataInventorySource(playerData, new ItemBytes.OfTheServer()),
                who -> Bukkit.getPlayer(who) != null);
    }

    public Inventories(Plugin plugin, InventoryViews views, OfflineEdits offlineEdits,
                       InventorySource online, PlayerDataInventorySource saved,
                       Predicate<UUID> isOnline) {
        this.plugin = plugin;
        this.views = views;
        this.offlineEdits = offlineEdits;
        this.online = online;
        this.saved = saved;
        this.isOnline = isOnline;
    }

    // ------------------------------------------------------------------------------- looking

    /** Which source somebody's items come from right now. */
    public InventorySource sourceFor(UUID who) {
        return isOnline.test(who) ? online : saved;
    }

    /** What somebody is carrying, wherever it has to be read from. */
    public Optional<Carried<ItemStack>> read(UUID who) {
        return who == null ? Optional.empty() : sourceFor(who).read(who);
    }

    /** Whether this server has ever saved them — the honest form of "does this player exist". */
    public boolean knows(UUID who) {
        return who != null && (isOnline.test(who) || saved.has(who));
    }

    /**
     * Opens a window onto somebody.
     *
     * <p>Answered through a callback rather than returned, because reading a logged-out player
     * means reading and un-gzipping a file, and doing that on the thread a command arrived on is a
     * stall on the thread that is also running the world. Everything that can be decided without
     * touching the disk is decided immediately; the rest happens off the server's threads and comes
     * back on the moderator's own.
     *
     * <p>The order of the two locks matters. The file is claimed <em>before</em> it is read, so that
     * a player joining at any point from here on is noticed and the edit yields to them rather than
     * being written over the top of a player the server has since loaded.
     *
     * @param then told what happened, always on the moderator's own thread
     */
    public void open(Player watcher, UUID owner, String ownerName, Access wanted,
                     Consumer<Outcome> then) {
        Consumer<Outcome> answer = then == null ? outcome -> { } : then;
        if (watcher == null || owner == null) {
            answer.accept(Outcome.NOT_ALLOWED);
            return;
        }
        if (watcher.getUniqueId().equals(owner)) {
            answer.accept(Outcome.YOURSELF);
            return;
        }
        Access level = wanted == null ? Access.READ_ONLY : wanted;
        boolean live = isOnline.test(owner);
        if (live) {
            // Nothing to read from disk: it is all in memory, and it has to be read on the thread
            // that owns the player anyway.
            finishOpening(watcher, owner, ownerName, level, true, online.read(owner), answer);
            return;
        }
        if (!saved.has(owner)) {
            answer.accept(Outcome.NEVER_SEEN);
            return;
        }
        if (level.canEdit() && !offlineEdits.begin(owner, watcher.getUniqueId())) {
            answer.accept(Outcome.BEING_EDITED);
            return;
        }
        Scheduling.async(plugin, () -> {
            Optional<Carried<ItemStack>> carried = saved.read(owner);
            Scheduling.entity(plugin, watcher, () ->
                    finishOpening(watcher, owner, ownerName, level, false, carried, answer));
        });
    }

    /** The half that has to happen on the moderator's thread: opening the window. */
    private void finishOpening(Player watcher, UUID owner, String ownerName, Access level,
                               boolean live, Optional<Carried<ItemStack>> carried,
                               Consumer<Outcome> answer) {
        if (carried.isEmpty()) {
            offlineEdits.finish(owner, watcher.getUniqueId());
            answer.accept(Outcome.UNREADABLE);
            return;
        }
        if (!watcher.isOnline()) {
            // They left while their file was being read. Nothing to open, and the hold has to go.
            offlineEdits.finish(owner, watcher.getUniqueId());
            answer.accept(Outcome.NOT_ALLOWED);
            return;
        }
        if (!views.open(watcher.getUniqueId(), owner, level)) {
            offlineEdits.finish(owner, watcher.getUniqueId());
            answer.accept(Outcome.BEING_EDITED);
            return;
        }
        InventoryWindow window = new InventoryWindow(plugin, watcher, owner,
                nameOf(owner, ownerName), level, live, sourceFor(owner), carried.get());
        window.open();
        log.info("{} is {} the inventory of {} ({}).", watcher.getName(),
                level.saying().toLowerCase(), nameOf(owner, ownerName),
                live ? "online" : "from their save file");
        answer.accept(Outcome.OPENED);
    }

    private String nameOf(UUID who, String given) {
        if (given != null && !given.isBlank()) {
            return given;
        }
        OfflinePlayer known = Bukkit.getOfflinePlayer(who);
        return known.getName() == null ? who.toString() : known.getName();
    }

    // ------------------------------------------------------------------------------ closing

    /**
     * A window has closed: the offline half is written and both locks are let go.
     *
     * <p>The write happens here rather than on each click because it is a whole file. It is also the
     * last moment at which it can happen, which is why a failure is logged loudly rather than
     * swallowed: a moderator who is told nothing assumes their change took.
     *
     * @return whether everything that needed writing was written
     */
    public void closed(InventoryWindow window) {
        if (window == null) {
            return;
        }
        UUID watcher = window.watcher().getUniqueId();
        views.close(watcher);
        if (window.isLive() || !window.access().canEdit()) {
            // Nothing to write: a live inventory was changed as it went, and a read-only window
            // changed nothing at all.
            offlineEdits.finish(window.owner(), watcher);
            return;
        }
        if (!offlineEdits.isStillTheirs(window.owner(), watcher)
                && !isOnline.test(window.owner())) {
            // Their hold lapsed while the window sat open — a moderator who went to make tea. The
            // hold exists to stop a second moderator, not to void the first one's work, and the
            // owner is still away, so it is taken again rather than the edit being thrown out. If
            // somebody else has it by now, this fails and the change is given back below.
            offlineEdits.begin(window.owner(), watcher);
        }
        Carried<ItemStack> toWrite = window.carried();
        Scheduling.async(plugin, () -> {
            // Asked and done in one atomic step. The other thing that can happen at this instant is
            // the owner logging in, and "check, then write" as two steps is a write that lands
            // after the server has already read that file — thrown away at best. The file write is
            // inside that step on purpose; it is a few kilobytes of gzip, and the alternative is a
            // window in which a login can be missed.
            boolean written = offlineEdits.writeAndFinish(window.owner(), watcher,
                    () -> saved.write(window.owner(), toWrite));
            if (written) {
                return;
            }
            Scheduling.entity(plugin, window.watcher(), () -> {
                // Closing a window destroys what is in it, so anything the moderator added that is
                // not now the owner's has to go back to them. Without this, "the change was not
                // written" quietly means "the items are gone".
                window.giveBackAdditions();
                told(watcher, Component.text("Their inventory was not saved — nothing was changed, "
                                + "and what you added is back with you.")
                        .color(NamedTextColor.RED));
            });
            log.info("{}'s changes to {} were not written. Their file is untouched and the items "
                    + "are back with the moderator.", window.watcher().getName(),
                    window.ownerName());
        });
    }

    /** Says the moderator is still there, so an offline hold does not expire under them. */
    public void stillLooking(InventoryWindow window) {
        if (window != null && !window.isLive()) {
            offlineEdits.touch(window.owner(), window.watcher().getUniqueId());
        }
    }

    // -------------------------------------------------------------------------------- rules

    /**
     * Somebody has logged in, and any edit of their save file yields to them.
     *
     * <p>Nobody is ever held out of the server for this. A player turned away by a server they have
     * done nothing wrong on concludes it is broken, and no moderator's convenience is worth that.
     * So the arriving player wins: every window onto them is shut, the pending write is cancelled,
     * and their file is left exactly as it was.
     *
     * <p>Nothing real is lost, because nothing had been written. The moderator is told what happened
     * and what to do instead, which is to open the now-live inventory and make the change there —
     * where it takes effect immediately and cannot be overwritten by anybody.
     *
     * @return who was editing them, if anybody
     */
    public Optional<UUID> somebodyJoined(UUID who) {
        Optional<UUID> editor = offlineEdits.ownerCameBack(who);
        // Shuts every window onto them, editor and onlookers alike: a window showing a file that
        // is no longer the truth is a window that will be acted on.
        Set<UUID> watchers = views.ownerLeft(who);
        editor.ifPresent(moderator -> tell(moderator, who));
        watchers.stream()
                .filter(watcher -> editor.filter(watcher::equals).isEmpty())
                .forEach(watcher -> told(watcher,
                        Component.text("They just logged in — the window was showing their saved "
                                + "file, which is no longer where their things are. Open it again "
                                + "to see them live.").color(NamedTextColor.GRAY)));
        return editor;
    }

    private void tell(UUID moderator, UUID owner) {
        told(moderator, Component.text(nameOf(owner, null) + " logged in before you closed the "
                        + "window, so your changes were not saved — their file is untouched. "
                        + "Open their inventory again to change it live.")
                .color(NamedTextColor.GOLD));
    }

    private void told(UUID who, Component message) {
        Player player = Bukkit.getPlayer(who);
        if (player != null) {
            player.sendMessage(message);
        }
    }

    /** Who is editing them, so the refusal can say how long it is likely to be. */
    public Optional<UUID> editorOf(UUID who) {
        return offlineEdits.editorOf(who);
    }

    public InventoryViews views() {
        return views;
    }

    public OfflineEdits offlineEdits() {
        return offlineEdits;
    }

    /** The saved-file source, for a plugin that wants to read one without opening a window. */
    public PlayerDataInventorySource saved() {
        return saved;
    }

    /** Clears out abandoned holds. Called on the same timer as everything else that sweeps. */
    public int sweep() {
        return offlineEdits.sweep();
    }
}
