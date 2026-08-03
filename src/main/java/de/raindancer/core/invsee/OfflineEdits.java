package de.raindancer.core.invsee;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Who is being edited while they are logged out, and what happens when they come back.
 *
 * <h2>The problem this solves</h2>
 * A logged-out player's inventory lives in a file, and that file is only the truth while they are
 * away. The server reads it when they join and writes it back when they leave, so an edit made
 * across either of those moments is discarded without a word — a moderator watches their change go
 * in and finds it undone an hour later.
 *
 * <h2>Why it does not solve it by holding the player out</h2>
 * Because a plugin that stops somebody logging in is worse than the problem it is preventing. A
 * player who is told "try again in a moment" by a server they have done nothing wrong on does not
 * read the reason; they conclude the server is broken. So the rule here is the other way round:
 *
 * <blockquote><b>The player always wins. The edit yields.</b></blockquote>
 *
 * <p>When somebody logs in while their saved inventory is open in a window, the hold is
 * <em>superseded</em>: the window shuts, nothing is written, and the moderator is told plainly that
 * the player arrived and their changes were dropped — with the obvious next step, which is to open
 * the now-live inventory and make the change there. Nothing is lost that was ever real, because
 * nothing was written; the file on disk is exactly as its owner left it.
 *
 * <p>The half-written file case does not arise at all: files are written beside and moved into
 * place, so a join either sees the old file whole or the new one whole — see {@code Nbt.write}.
 *
 * <h2>Why a hold expires</h2>
 * Because the moderator holding one can crash, lose connection or walk away with the window open,
 * and an abandoned hold would keep the next moderator out of an inventory for ever.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread, and every decision is one atomic step on one key. That matters here more
 * than anywhere else in the package: a login arrives on a different thread from the window close,
 * and "check whether the player came back, then write" as two steps is precisely the race this
 * class exists to close.
 */
public final class OfflineEdits {

    private static final LogChannel log = Log.of("invsee");

    /** Long enough for a real edit, short enough that an abandoned one is not somebody's evening. */
    public static final Duration DEFAULT_LONGEST = Duration.ofMinutes(5);

    private final LongSupplier clock;
    private final long longestMillis;

    /** Who is editing each logged-out player, and since when they last showed a sign of life. */
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();

    /**
     * @param superseded set when the owner logged back in, which ends the edit without writing it
     */
    private record Held(UUID moderator, long since, boolean superseded) {

        Held at(long now) {
            return new Held(moderator, now, superseded);
        }

        Held yielded() {
            return new Held(moderator, since, true);
        }
    }

    public OfflineEdits(LongSupplier clock) {
        this(clock, DEFAULT_LONGEST);
    }

    public OfflineEdits(LongSupplier clock, Duration longest) {
        this.clock = clock;
        this.longestMillis = longest == null ? DEFAULT_LONGEST.toMillis()
                : Math.max(1_000L, longest.toMillis());
    }

    // ---------------------------------------------------------------------------- taking it

    /**
     * Takes the hold on one logged-out player.
     *
     * @return whether it was given; false when somebody else already has it
     */
    public boolean begin(UUID owner, UUID moderator) {
        if (owner == null || moderator == null) {
            return false;
        }
        long now = clock.getAsLong();
        // One call, so two moderators arriving at the same instant cannot both be told yes. The
        // same moderator asking again refreshes their own hold rather than being refused — and,
        // just as importantly, rather than releasing it on the way past, which is exactly the bug
        // InventoryViews had.
        Held holder = held.compute(owner, (key, existing) -> {
            if (existing == null || hasExpired(existing, now)
                    || existing.moderator().equals(moderator)) {
                return new Held(moderator, now, false);
            }
            return existing;
        });
        boolean ours = holder.moderator().equals(moderator) && !holder.superseded();
        if (ours) {
            log.debug("{} is editing the saved inventory of {}.", moderator, owner);
        }
        return ours;
    }

    /** Says the holder is still there, so the hold does not expire under them. */
    public boolean touch(UUID owner, UUID moderator) {
        if (owner == null || moderator == null) {
            return false;
        }
        long now = clock.getAsLong();
        AtomicBoolean refreshed = new AtomicBoolean();
        held.computeIfPresent(owner, (key, existing) -> {
            if (!existing.moderator().equals(moderator) || existing.superseded()
                    || hasExpired(existing, now)) {
                return existing;
            }
            refreshed.set(true);
            return existing.at(now);
        });
        return refreshed.get();
    }

    // ------------------------------------------------------------------- the owner comes back

    /**
     * The owner has logged in. Their edit yields, and nothing of it will be written.
     *
     * <p>Called before the server has loaded them, so the decision is made once and cannot be
     * overtaken by a window closing a moment later: from here on {@link #writeAndFinish} refuses.
     *
     * @return who was editing them, so they can be told to their face — empty when nobody was
     */
    public Optional<UUID> ownerCameBack(UUID owner) {
        if (owner == null) {
            return Optional.empty();
        }
        Held existing = held.computeIfPresent(owner, (key, holder) -> holder.yielded());
        if (existing == null) {
            return Optional.empty();
        }
        log.info("{} logged in while {} was editing their saved inventory. The player wins: the "
                + "window is closed and nothing was written to their file.", owner,
                existing.moderator());
        return Optional.of(existing.moderator());
    }

    /** Whether an edit is still the moderator's to finish — false once its owner has come back. */
    public boolean isStillTheirs(UUID owner, UUID moderator) {
        return current(owner)
                .filter(holder -> holder.moderator().equals(moderator))
                .filter(holder -> !holder.superseded())
                .isPresent();
    }

    /**
     * Writes an offline edit, if it is still one, and lets the hold go either way.
     *
     * <p>The write happens <em>inside</em> the same atomic step that checks whether it may, because
     * the two events being ordered here — a player logging in and a moderator closing a window —
     * genuinely arrive on different threads. Checking and then writing, as two steps, is a write
     * that lands a moment after the server has already loaded the player from that file, and
     * therefore a write that is silently thrown away.
     *
     * @param write what to actually do, run only if the edit is still valid
     * @return whether it was written
     */
    public boolean writeAndFinish(UUID owner, UUID moderator, BooleanSupplier write) {
        if (owner == null || moderator == null || write == null) {
            return false;
        }
        AtomicBoolean written = new AtomicBoolean();
        held.computeIfPresent(owner, (key, existing) -> {
            if (!existing.moderator().equals(moderator)) {
                // Not theirs to finish, and not theirs to release either.
                return existing;
            }
            if (!existing.superseded() && !hasExpired(existing, clock.getAsLong())) {
                written.set(write.getAsBoolean());
            }
            // Gone either way: the window is closed, so the hold has no owner any more.
            return null;
        });
        return written.get();
    }

    // --------------------------------------------------------------------------- letting go

    /**
     * Lets go of one hold.
     *
     * <p>Only the moderator who took it may, which is what stops a second moderator closing their
     * own read-only window from releasing somebody else's edit.
     *
     * @return whether there was one of theirs to let go of
     */
    public boolean finish(UUID owner, UUID moderator) {
        if (owner == null || moderator == null) {
            return false;
        }
        AtomicBoolean released = new AtomicBoolean();
        held.computeIfPresent(owner, (key, existing) -> {
            if (!existing.moderator().equals(moderator)) {
                return existing;
            }
            released.set(true);
            return null;
        });
        return released.get();
    }

    /**
     * A moderator has gone. Everything they held is let go.
     *
     * @return the players they were editing, so a log line can name them
     */
    public Set<UUID> editorLeft(UUID moderator) {
        if (moderator == null) {
            return Set.of();
        }
        Set<UUID> theirs = held.entrySet().stream()
                .filter(entry -> entry.getValue().moderator().equals(moderator))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
        theirs.forEach(owner -> finish(owner, moderator));
        return theirs;
    }

    /** Lets go of everything — for a shutdown. Answers how many. */
    public int finishEverything() {
        int howMany = held.size();
        held.clear();
        return howMany;
    }

    /**
     * Clears out holds nobody let go of.
     *
     * @return how many were cleared
     */
    public int sweep() {
        long now = clock.getAsLong();
        int cleared = 0;
        for (Map.Entry<UUID, Held> entry : held.entrySet()) {
            if (hasExpired(entry.getValue(), now) && held.remove(entry.getKey(), entry.getValue())) {
                cleared++;
                log.info("The offline edit of {} by {} was abandoned and has been let go.",
                        entry.getKey(), entry.getValue().moderator());
            }
        }
        return cleared;
    }

    // ------------------------------------------------------------------------------ asking

    /** Whether somebody's saved inventory is being edited right now. */
    public boolean isBeingEdited(UUID owner) {
        return current(owner).filter(holder -> !holder.superseded()).isPresent();
    }

    /** Who is editing them, if anybody — so a message can name them. */
    public Optional<UUID> editorOf(UUID owner) {
        return current(owner).filter(holder -> !holder.superseded()).map(Held::moderator);
    }

    /** How many holds there are. */
    public int size() {
        sweep();
        return held.size();
    }

    /** The hold on somebody, if there is one that has not expired. */
    private Optional<Held> current(UUID owner) {
        if (owner == null) {
            return Optional.empty();
        }
        Held existing = held.get(owner);
        if (existing == null) {
            return Optional.empty();
        }
        if (hasExpired(existing, clock.getAsLong())) {
            // Cleared as it is found: an expired hold that stays in the map is an inventory no
            // other moderator can open, held by somebody who left hours ago.
            held.remove(owner, existing);
            return Optional.empty();
        }
        return Optional.of(existing);
    }

    private boolean hasExpired(Held holder, long now) {
        return now - holder.since() > longestMillis;
    }
}
