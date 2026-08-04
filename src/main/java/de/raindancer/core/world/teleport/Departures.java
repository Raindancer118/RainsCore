package de.raindancer.core.world.teleport;

import de.raindancer.core.world.safety.Spot;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is part-way through being sent somewhere, and what a second of waiting does to them.
 *
 * <h2>Why this exists</h2>
 * "Stand still for three seconds" had been written twice — once in the teleport requests and once in
 * homes — identically enough that the {@code sameBlock} helper matched byte for byte. Two copies is
 * two places to fix the same bug in one of, and this is the one of them. {@link Travel} is the half
 * that talks to Bukkit; this is the half that decides, which is why it can be tested at all.
 *
 * <h2>What it does not do</h2>
 * It does not schedule, it does not teleport and it does not send anything. It is a record of who is
 * waiting and a set of rules about that. {@link #hasMoved} in particular only <em>answers</em>:
 * cancelling inside the question would take from every caller the decision about what a step costs,
 * and some callers forgive one.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread, and it has to be: on Folia the same player's movement, damage and command
 * can reach different region threads. {@link #begin} and {@link #tick} are single operations on a
 * {@link ConcurrentHashMap}, so two commands arriving together start one warm-up and two ticks on
 * the last second arrive once.
 */
public final class Departures {

    /** What a second of waiting came to. */
    public enum Tick {

        /** Still counting. */
        WAITING,

        /** The countdown is over — the caller does the teleport, and the departure is forgotten. */
        ARRIVED,

        /** Nobody by that name is going anywhere; a stray tick from a task already cancelled. */
        NOTHING_PENDING
    }

    private final Map<UUID, Departure> waiting = new ConcurrentHashMap<>();

    /**
     * Starts somebody's warm-up.
     *
     * <p>Refused when they are already waiting for something, rather than replacing it: replacing
     * would let a player type the command again to restart the countdown from wherever they have
     * walked to, which is a warm-up that can be strolled through.
     *
     * @param from    the block they are standing on; moving off it is what {@link #hasMoved} means
     * @param seconds how long; zero is not a warm-up and is refused, so that a caller who goes
     *                straight there cannot have the teleport cancelled by a movement event
     * @return the departure, or empty when there is not one to make
     */
    public Optional<Departure> begin(UUID traveller, Spot from, int seconds, String what) {
        if (traveller == null || from == null || seconds <= 0) {
            return Optional.empty();
        }
        Departure started = new Departure(traveller, from, seconds, what);
        return waiting.putIfAbsent(traveller, started) == null
                ? Optional.of(started)
                : Optional.empty();
    }

    /** What this player is waiting for, if anything. */
    public Optional<Departure> pending(UUID traveller) {
        return traveller == null ? Optional.empty() : Optional.ofNullable(waiting.get(traveller));
    }

    public boolean isLeaving(UUID traveller) {
        return pending(traveller).isPresent();
    }

    /**
     * One second gone.
     *
     * <p>{@link Tick#ARRIVED} forgets the departure in the same operation that reports it, so a
     * repeating task that fires once more before it is cancelled cannot teleport somebody twice —
     * and the second one lands them where they already are, which reads as the plugin moving people
     * about at random.
     */
    public Tick tick(UUID traveller) {
        if (traveller == null) {
            return Tick.NOTHING_PENDING;
        }
        Departure[] outcome = new Departure[1];
        boolean[] wasThere = new boolean[1];
        waiting.compute(traveller, (ignored, current) -> {
            if (current == null) {
                return null;
            }
            wasThere[0] = true;
            Departure next = current.aSecondOn();
            outcome[0] = next;
            return next.isDue() ? null : next;
        });
        if (!wasThere[0]) {
            return Tick.NOTHING_PENDING;
        }
        return outcome[0].isDue() ? Tick.ARRIVED : Tick.WAITING;
    }

    /**
     * Whether this player has left the block they were standing on when they asked.
     *
     * <p>Answers only. False when they are not going anywhere, so a movement event for an ordinary
     * player costs one map lookup and nothing else.
     */
    public boolean hasMoved(UUID traveller, Spot now) {
        return pending(traveller).map(departure -> departure.isAwayFrom(now)).orElse(false);
    }

    /**
     * Gives up on a warm-up — because they moved, were hurt, or logged out.
     *
     * <p>There is deliberately no {@code forget(UUID)} beside this. Forgetting a player who has
     * quit and cancelling a warm-up are the same operation on the same entry, and a class with two
     * names for one thing is a class where half the callers use the one that was not fixed. What
     * calls this when somebody leaves is {@link Travel#forget}, through {@link TravelListener}.
     *
     * @return true when there was one, so a caller can tell "they were going somewhere and now are
     *         not" from "nothing was happening" and only say something in the first case
     */
    public boolean cancel(UUID traveller) {
        return traveller != null && waiting.remove(traveller) != null;
    }

    /** Forgets everybody. For a plugin being disabled. */
    public void clear() {
        waiting.clear();
    }

    /** How many are waiting, for a diagnostic and for the tests. */
    public int pendingCount() {
        return waiting.size();
    }
}
