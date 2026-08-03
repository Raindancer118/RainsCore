package de.raindancer.core.moderation.vanish;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Being properly not here.
 *
 * <h2>Why this is Core's</h2>
 * Because vanish is not one feature — it is a promise every other feature has to keep. Somebody
 * hidden who still appears in the tablist, still counts in "3 players online", or whose join message
 * went out anyway is not hidden, and each of those belongs to a different subsystem. Only the thing
 * that owns the tablist, the chat and the player list can make the promise hold.
 *
 * <p>The practical consequence for a plugin is one line: ask {@link #visibleOf} instead of
 * {@code Bukkit.getOnlinePlayers()}, and {@link #isVanished} before mentioning anybody. Nine plugins
 * each keeping their own set is nine chances for five of them to forget.
 *
 * <h2>Why the extras are optional</h2>
 * Flight, gamemode and the rest come apart in practice: somebody may want to be invisible without
 * flying, or to look at a build in creative without being hidden. Bundling them means the one you
 * did not want comes along too, and turning it off afterwards is what leaves a moderator stuck in
 * survival at bedrock. Flight is remembered as it was and put back exactly as it was found.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread.
 */
public final class Vanish {

    private static final LogChannel log = Log.of("vanish");

    private final VanishSink sink;

    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    private final Set<UUID> maySee = ConcurrentHashMap.newKeySet();
    /** Who could already fly before they vanished, so they are not grounded when they come back. */
    private final Set<UUID> couldAlreadyFly = ConcurrentHashMap.newKeySet();

    private volatile boolean flightWhileVanished = true;

    public Vanish(VanishSink sink) {
        this.sink = sink;
    }

    // ---------------------------------------------------------------------------- settings

    /**
     * Whether vanishing also grants flight.
     *
     * <p>On by default, because somebody who is invisible and walking is somebody whose footsteps
     * and door-opening give them away. Off for a server that would rather keep the two apart.
     */
    public void flightWhileVanished(boolean granted) {
        this.flightWhileVanished = granted;
    }

    public boolean isFlightWhileVanished() {
        return flightWhileVanished;
    }

    // ---------------------------------------------------------------------------- going

    /** Hides somebody. Answers whether this changed anything. */
    public boolean vanish(UUID who) {
        return vanish(who, false);
    }

    /**
     * Hides somebody, remembering whether they could already fly.
     *
     * @param couldFlyAlready whether flight was already theirs — a creative builder, or somebody
     *                        with the permission. Passing this stops {@link #reveal} taking away
     *                        something it never gave, which is how a builder lands in the void.
     */
    public boolean vanish(UUID who, boolean couldFlyAlready) {
        if (who == null || !hidden.add(who)) {
            // Already hidden. Re-hiding re-sends packets to every player on the server for nothing.
            return false;
        }
        if (couldFlyAlready) {
            couldAlreadyFly.add(who);
        }
        sink.hide(who, Set.copyOf(maySee));
        sink.collidable(who, false);
        sink.silentJoinLeave(who, true);
        if (flightWhileVanished && !couldFlyAlready) {
            sink.allowFlight(who, true);
        }
        log.info("{} vanished.", who);
        return true;
    }

    /** Brings somebody back. Answers whether they were hidden. */
    public boolean reveal(UUID who) {
        if (who == null || !hidden.remove(who)) {
            return false;
        }
        sink.show(who);
        sink.collidable(who, true);
        sink.silentJoinLeave(who, false);
        if (flightWhileVanished && !couldAlreadyFly.remove(who)) {
            // Only what was granted is taken back. A creative-mode builder who vanished must not
            // land in the void when they return.
            sink.allowFlight(who, false);
        }
        log.info("{} is visible again.", who);
        return true;
    }

    /** Hides or reveals. Answers whether they are now hidden. */
    public boolean toggle(UUID who) {
        return isVanished(who) ? !reveal(who) : vanish(who);
    }

    /** Brings everybody back — for a shutdown. Answers how many. */
    public int revealEverybody() {
        int count = 0;
        for (UUID who : List.copyOf(hidden)) {
            if (reveal(who)) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------------------- asking

    public boolean isVanished(UUID who) {
        return who != null && hidden.contains(who);
    }

    /** Everybody hidden. */
    public Set<UUID> everybodyVanished() {
        return Set.copyOf(hidden);
    }

    /**
     * The ones a plugin should treat as online.
     *
     * <p>The call to make instead of {@code getOnlinePlayers()}. Nearly every place vanish leaks is
     * a place that skipped it.
     */
    public List<UUID> visibleOf(Collection<UUID> players) {
        return players == null ? List.of()
                : players.stream().filter(who -> !isVanished(who)).toList();
    }

    /** How many of these count as online. */
    public int countOf(Collection<UUID> players) {
        return visibleOf(players).size();
    }

    /**
     * Whether one player can see another.
     *
     * <p>Somebody can always see themselves — a moderator who cannot has been made to disappear
     * rather than hidden — and anybody allowed to see hidden players can see all of them, so staff
     * do not spend the night walking into each other.
     */
    public boolean canSee(UUID viewer, UUID target) {
        if (viewer == null || target == null) {
            return false;
        }
        return viewer.equals(target) || !isVanished(target) || maySeeVanished(viewer);
    }

    /** Whether somebody is allowed to see hidden players. */
    public boolean maySeeVanished(UUID who) {
        return who != null && maySee.contains(who);
    }

    /** Says whether somebody may see hidden players — from a permission, usually, on join. */
    public void maySeeVanished(UUID who, boolean may) {
        if (who == null) {
            return;
        }
        if (may) {
            maySee.add(who);
        } else {
            maySee.remove(who);
        }
    }

    // ---------------------------------------------------------------------------- leaving

    /**
     * Forgets what was only true for this visit, keeping whether they are hidden.
     *
     * <p>Called when somebody leaves. Being hidden deliberately survives: a moderator who reconnects
     * and is suddenly visible has been given away by the plugin that was hiding them.
     */
    public void forgetSession(UUID who) {
        if (who != null) {
            maySee.remove(who);
        }
    }

    /** Forgets somebody entirely — for a player removed from the server. */
    public void forget(UUID who) {
        if (who != null) {
            hidden.remove(who);
            maySee.remove(who);
            couldAlreadyFly.remove(who);
        }
    }
}
