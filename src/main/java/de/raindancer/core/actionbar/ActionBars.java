package de.raindancer.core.actionbar;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
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

    /** One plugin's claim on one player's action bar. */
    private record Entry(Component text, ActionBarPriority priority, long expiresAt, long shownAt) {

        boolean isLiveAt(long now) {
            return expiresAt == 0L || now < expiresAt;
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
        /** What was last handed to the sink, so an unchanged bar is not sent again. */
        private Component onScreen;
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

        Slot slot = slots.computeIfAbsent(player, key -> new Slot());
        synchronized (slot) {
            slot.byOwner.put(who, new Entry(message, priority == null
                    ? ActionBarPriority.NORMAL : priority, expiresAt, now));
            repaint(player, slot, now);
        }
    }

    /** Takes away what this owner was showing. Harmless when it was showing nothing. */
    public void clear(UUID player, String owner) {
        if (player == null || owner == null) {
            return;
        }
        Slot slot = slots.get(player);
        if (slot == null) {
            return;
        }
        synchronized (slot) {
            if (slot.byOwner.remove(owner.trim()) == null) {
                return;
            }
            repaint(player, slot, clock.getAsLong());
            forgetIfIdle(player, slot);
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
                Entry winner = winnerOf(slot, now);
                if (winner == null) {
                    // Cleared once, when the last message went; not on every tick afterwards.
                    if (slot.onScreen != null) {
                        send(each.getKey(), Component.empty());
                        slot.onScreen = null;
                    }
                    players.remove();
                    continue;
                }
                slot.onScreen = winner.text();
                send(each.getKey(), winner.text());
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
        dropExpired(slot, now);
        Entry winner = winnerOf(slot, now);
        Component wanted = winner == null ? Component.empty() : winner.text();
        if (wanted.equals(slot.onScreen)) {
            return;
        }
        slot.onScreen = winner == null ? null : wanted;
        send(player, wanted);
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
