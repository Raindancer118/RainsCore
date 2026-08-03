package de.raindancer.core.ui.actionbar;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

/**
 * Who gets the action bar.
 *
 * <h2>Why this owns the slot instead of wrapping {@code sendActionBar}</h2>
 * A player has one action bar. The ghast lines write flight commentary to it every tick, the claims
 * write "you have entered Raindancer118's claim" to it on a move, and homes writes "home set" when
 * somebody types the command — none of them knowing the others exist. What a player sees is whoever
 * wrote last, which changes several times a second while a ghast is in the air. A helper that only
 * wrapped the send would not improve that by one line.
 *
 * <p>So this owns the slot and the callers ask. A message has an <em>owner</em> (the plugin or
 * subsystem), a {@link ActionBarPriority} and a lifetime. The highest-priority live message wins,
 * ties go to the most recent, and when the winner expires whatever it interrupted comes back — a
 * flight that was interrupted by a refusal resumes its commentary instead of going silent.
 *
 * <h2>Why it ticks</h2>
 * The client fades an action bar after about three seconds whatever the server intended, so a
 * message that is supposed to stay has to be re-sent. {@link #tick()} does that, and is also where
 * expiry is noticed. It is cheap by design: a player with nothing to show costs nothing, and an
 * unchanged bar is re-sent rather than recomputed.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread, which on Folia means every thread. State is a {@link ConcurrentHashMap} of
 * per-player records, and each player's record is only ever mutated under its own lock — so two
 * plugins writing to two players never contend, and two writing to the same player interleave
 * safely.
 */
public final class ActionBars {

    /** A lifetime meaning "until somebody clears it" — for a running commentary. */
    public static final Duration UNTIL_CLEARED = Duration.ZERO;

    private static final LogChannel log = Log.of("actionbar");

    /**
     * How often unchanged text is sent again.
     *
     * <p>The client fades an action bar after about three seconds whatever the server intended, so
     * something meant to stay has to be repeated — but repeating it on every tick is twenty packets
     * a second per player to say nothing new. Once a second is comfortably inside the fade and costs
     * a twentieth of that. A change is always sent at once and does not wait for this.
     */
    private static final long REFRESH_MILLIS = 1_000L;

    /**
     * One plugin's claim on one player's action bar.
     *
     * <p>The text is a function of how long is left rather than a fixed component, which is what
     * lets a countdown redraw itself without the caller sending a new message every tick. A fixed
     * message is the degenerate case: a function that ignores its argument.
     */
    private record Entry(LongFunction<Component> frame, ActionBarPriority priority, long expiresAt,
                         long shownAt) {

        static Entry fixed(Component text, ActionBarPriority priority, long expiresAt, long shownAt) {
            return new Entry(remaining -> text, priority, expiresAt, shownAt);
        }

        boolean isLiveAt(long now) {
            return expiresAt == 0L || now < expiresAt;
        }

        /** How long this has left, in milliseconds; {@link Long#MAX_VALUE} when it never expires. */
        long remainingAt(long now) {
            return expiresAt == 0L ? Long.MAX_VALUE : Math.max(0L, expiresAt - now);
        }

        /** Higher priority first; on a tie the more recent, so an answer replaces an answer. */
        boolean beats(Entry other) {
            if (other == null) {
                return true;
            }
            int byPriority = Integer.compare(priority.ordinal(), other.priority.ordinal());
            return byPriority != 0 ? byPriority > 0 : shownAt >= other.shownAt;
        }
    }

    /**
     * What one player is being shown, and by whom.
     *
     * <p>A {@link LinkedHashMap} keyed by owner, so an owner has exactly one message and showing
     * again replaces its own rather than stacking. Every read and write holds the record's monitor.
     */
    private static final class Slot {
        private final Map<String, Entry> byOwner = new LinkedHashMap<>();
        /** What was last handed to the sink, so an unchanged bar is not sent again at once. */
        private Component onScreen;
        /** When that happened, so unchanged text is still refreshed before the client fades it. */
        private long sentAt;
    }

    private final ActionBarSink sink;
    private final LongSupplier clock;
    private final Map<UUID, Slot> slots = new ConcurrentHashMap<>();

    /**
     * @param sink  how a line reaches a player
     * @param clock milliseconds; injected so expiry can be tested without waiting for it
     */
    public ActionBars(ActionBarSink sink, LongSupplier clock) {
        this.sink = sink;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------- showing

    /**
     * Shows a message, or replaces the one this owner is already showing.
     *
     * @param player   whose bar
     * @param owner    who is asking — a plugin or subsystem name, never blank
     * @param message  what to show; {@code null} clears this owner instead
     * @param lifetime how long it stays, or {@link #UNTIL_CLEARED}
     * @param priority how badly it is wanted
     */
    public void show(UUID player, String owner, Component message, Duration lifetime,
                     ActionBarPriority priority) {
        if (player == null) {
            return;
        }
        String who = owner == null ? "" : owner.trim();
        if (who.isEmpty()) {
            // A blank owner would be a bucket every careless caller shared, so one plugin's message
            // would silently replace another's and clearing would take away both.
            log.warn("An action bar message for {} was refused: it named no owner.", player);
            return;
        }
        if (message == null) {
            clear(player, who);
            return;
        }
        long now = clock.getAsLong();
        long expiresAt = lifetime == null || lifetime.isZero() ? 0L : now + lifetime.toMillis();
        if (expiresAt != 0L && expiresAt <= now) {
            // A negative lifetime is a bug at the call site. Treating it as "for ever" would leave a
            // message on screen until a restart, which is the worst possible reading of it.
            clear(player, who);
            return;
        }

        Entry entry = Entry.fixed(message, priority == null
                ? ActionBarPriority.NORMAL : priority, expiresAt, now);
        write(player, who, entry, now);
    }

    /**
     * Shows something that redraws itself as it runs out: a teleport countdown, a cast bar, the
     * seconds left before a ghast departs.
     *
     * <p>This is what the action bar is for — something that matters only for the second it is on
     * screen. The caller hands over a way to draw one frame and then forgets about it; there is no
     * repeating task to cancel and nothing to clean up if the player logs out halfway through.
     * {@link #clear} calls it off early, which is what {@code /tpcancel} needs.
     *
     * @param total how long it runs for; a countdown of no length is over before it starts
     * @param frame draws one frame, given the milliseconds remaining. Called on every tick, so it
     *              should be cheap and must not touch the world
     */
    public void countdown(UUID player, String owner, Duration total, ActionBarPriority priority,
                          LongFunction<Component> frame) {
        if (player == null || frame == null) {
            return;
        }
        String who = owner == null ? "" : owner.trim();
        if (who.isEmpty()) {
            log.warn("A countdown for {} was refused: it named no owner.", player);
            return;
        }
        if (total == null || total.isZero() || total.isNegative()) {
            clear(player, who);
            return;
        }
        long now = clock.getAsLong();
        write(player, who, new Entry(frame, priority == null
                ? ActionBarPriority.NORMAL : priority, now + total.toMillis(), now), now);
    }

    /**
     * Files one entry against a player, and repaints.
     *
     * <h2>Why this retries</h2>
     * The slot has to come out of the map before its lock can be taken, and in that window another
     * thread can clear the player's last message, find the slot idle and drop it from the map. A
     * write into that slot would reach the player — they would see the message — but nothing would
     * point at the slot any more, so {@link #tick()} would never refresh it and never expire it. The
     * line would fade after about three seconds and never come back, and this class would believe
     * nothing was being shown.
     *
     * <p>Every removal holds the slot's own lock, so a writer that has the lock and still finds its
     * slot in the map knows it cannot be removed until the lock is released. Finding a different
     * slot means it was removed; the loop takes the new one and tries again. A test reproduces the
     * unguarded version within a handful of rounds.
     */
    private void write(UUID player, String owner, Entry entry, long now) {
        while (true) {
            Slot slot = slots.computeIfAbsent(player, key -> new Slot());
            synchronized (slot) {
                if (slots.get(player) != slot) {
                    continue;
                }
                slot.byOwner.put(owner, entry);
                repaint(player, slot, now);
                return;
            }
        }
    }

    /** Takes away what this owner was showing. Harmless when it was showing nothing. */
    public void clear(UUID player, String owner) {
        if (player == null || owner == null) {
            return;
        }
        while (true) {
            Slot slot = slots.get(player);
            if (slot == null) {
                return;
            }
            synchronized (slot) {
                if (slots.get(player) != slot) {
                    // Removed under us; whatever is there now is somebody else's, so look again.
                    continue;
                }
                if (slot.byOwner.remove(owner.trim()) == null) {
                    return;
                }
                repaint(player, slot, clock.getAsLong());
                forgetIfIdle(player, slot);
                return;
            }
        }
    }

    /** Drops everything remembered about a player. Called when they log out. */
    public void forget(UUID player) {
        if (player != null) {
            slots.remove(player);
        }
    }

    // ---------------------------------------------------------------------------- ticking

    /**
     * Re-sends what should be on screen and notices what has expired.
     *
     * <p>Called on a repeating task, a few times a second. Cheap: a player with nothing to show is
     * removed rather than iterated for ever, and a bar whose content has not changed is re-sent
     * without being rebuilt.
     */
    public void tick() {
        long now = clock.getAsLong();
        for (Iterator<Map.Entry<UUID, Slot>> players = slots.entrySet().iterator();
             players.hasNext(); ) {
            Map.Entry<UUID, Slot> each = players.next();
            Slot slot = each.getValue();
            synchronized (slot) {
                dropExpired(slot, now);
                Component wanted = renderWinner(each.getKey(), slot, now);
                if (wanted == null) {
                    // Cleared once, when the last message went; not on every tick afterwards.
                    if (slot.onScreen != null) {
                        send(each.getKey(), Component.empty());
                        slot.onScreen = null;
                        slot.sentAt = now;
                    }
                    players.remove();
                    continue;
                }
                boolean changed = !wanted.equals(slot.onScreen);
                boolean stale = now - slot.sentAt >= REFRESH_MILLIS;
                slot.onScreen = wanted;
                if (changed || stale) {
                    slot.sentAt = now;
                    send(each.getKey(), wanted);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------- reading

    /** Whether anything of ours is on this player's bar right now. */
    public boolean isShowingAnything(UUID player) {
        Slot slot = player == null ? null : slots.get(player);
        if (slot == null) {
            return false;
        }
        synchronized (slot) {
            return winnerOf(slot, clock.getAsLong()) != null;
        }
    }

    /** Which players we are holding state for. Only the ones with something to show. */
    public Set<UUID> trackedPlayers() {
        return Set.copyOf(slots.keySet());
    }

    // -------------------------------------------------------------------------- internals

    /** Sends the current winner, but only when it differs from what is already on screen. */
    private void repaint(UUID player, Slot slot, long now) {
        Component rendered = renderWinner(player, slot, now);
        Component wanted = rendered == null ? Component.empty() : rendered;
        if (wanted.equals(slot.onScreen)) {
            // Already on screen. The tick refreshes it before the client fades it; a caller that
            // repeats itself does not need to send anything.
            return;
        }
        slot.onScreen = rendered;
        slot.sentAt = now;
        send(player, wanted);
    }

    /**
     * Drops what has expired, then draws whatever wins — or null when nothing does.
     *
     * <p>A frame that throws costs its own entry and nothing else. A countdown whose template is
     * broken is dropped rather than left on screen: it cannot draw itself, so it has nothing to
     * show, and letting it keep winning would block everything beneath it for ever.
     */
    private Component renderWinner(UUID player, Slot slot, long now) {
        dropExpired(slot, now);
        while (true) {
            Entry winner = winnerOf(slot, now);
            if (winner == null) {
                return null;
            }
            try {
                Component drawn = winner.frame().apply(winner.remainingAt(now));
                if (drawn != null) {
                    return drawn;
                }
            } catch (RuntimeException broken) {
                log.warn(broken, "An action bar frame for {} threw and was dropped.", player);
            }
            slot.byOwner.values().remove(winner);
        }
    }

    private static void dropExpired(Slot slot, long now) {
        slot.byOwner.entrySet().removeIf(entry -> !entry.getValue().isLiveAt(now));
    }

    private static Entry winnerOf(Slot slot, long now) {
        Entry best = null;
        for (Entry candidate : slot.byOwner.values()) {
            if (candidate.isLiveAt(now) && candidate.beats(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private void forgetIfIdle(UUID player, Slot slot) {
        if (slot.byOwner.isEmpty() && slot.onScreen == null) {
            slots.remove(player, slot);
        }
    }

    /**
     * One send, with the player's disconnection allowed for.
     *
     * <p>A player can log out between the decision and the send, and on Folia the send can land
     * after they are gone. That must cost this one line, not the tick — one player leaving would
     * otherwise stop every other player's bar from updating.
     */
    private void send(UUID player, Component message) {
        try {
            sink.send(player, message);
        } catch (RuntimeException gone) {
            log.debug("Could not put a message on {}'s action bar: {}", player, gone.toString());
        }
    }

    /** Every owner currently claiming this player's bar — for a diagnostic command. */
    public List<String> ownersFor(UUID player) {
        Slot slot = player == null ? null : slots.get(player);
        if (slot == null) {
            return List.of();
        }
        synchronized (slot) {
            return List.copyOf(new ArrayList<>(slot.byOwner.keySet()));
        }
    }
}
