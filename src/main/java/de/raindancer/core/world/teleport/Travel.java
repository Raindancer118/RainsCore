package de.raindancer.core.world.teleport;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.safety.Safety;
import de.raindancer.core.world.safety.Spot;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sending a player somewhere: the waiting, the checking and the moving.
 *
 * <h2>Why this is Core's</h2>
 * Because it had been written twice already — in the teleport requests and in homes — and the two
 * copies were identical down to the helper that decides whether somebody has moved. Warps make it
 * three, and three copies of "stand still for three seconds" is three places to fix the next bug in
 * one of. {@link Departures} holds the rules, which is the half a test can reach; this is the half
 * that talks to Bukkit.
 *
 * <h2>What it takes care of, and what it leaves alone</h2>
 * It owns the countdown, the cancelling, finding somewhere safe and the teleport itself. It words
 * nothing: every message goes through a {@link TravelWatcher}, because "Going home in 3…" and
 * "Warping to spawn in 3…" belong to the plugin, and a library that wrote them would be a library
 * deciding what the server sounds like.
 *
 * <p>It also does not listen for anything. {@link TravelListener} does that, and the host registers
 * it — a library that registered its own listeners would be a library whose behaviour a plugin
 * cannot switch off.
 *
 * <h2>Threads</h2>
 * The countdown runs on the traveller's own scheduler, which on Folia is the region thread that owns
 * them. The safety check is asynchronous and its answer arrives on whichever thread finished the
 * chunk load, so everything after it hops back onto the player before touching anything. Nothing
 * here ever blocks on {@code Safety}: joining that future on the server thread is a deadlock the
 * watchdog eventually kills, with nothing in the log to say why.
 */
public final class Travel {

    private static final LogChannel log = Log.of("travel");

    /** What a repeating tick is worth: one second. */
    private static final long A_SECOND_IN_TICKS = 20L;

    /** Everything about one journey, in one entry — three maps keyed alike is three to get wrong. */
    private record Journey(Location destination, Trip trip, TravelWatcher watcher,
                           ScheduledTask countdown) {
    }

    private final Plugin plugin;
    private final Safety safety;
    private final Departures departures = new Departures();
    private final Map<UUID, Journey> journeys = new ConcurrentHashMap<>();
    /**
     * Who has a journey in progress at all — warm-up or not.
     *
     * <p>{@link Departures} cannot serve for this: a trip with no warm-up never becomes a departure,
     * so two instant trips issued in the same tick both used to pass the "already travelling" check
     * and both teleported. Worse, a caller that charges its cooldown on arrival saw neither of them
     * as in progress and let a player warp as fast as they could type.
     *
     * <p>Entered with {@code add}, which answers whether it was already there — one atomic operation,
     * because on Folia two commands really can arrive on two threads.
     */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * @param safety where a safe arrival is worked out; null means every trip goes to its exact
     *               coordinates, which is what a server without Core's chunk holds gets
     */
    public Travel(Plugin plugin, Safety safety) {
        this.plugin = plugin;
        this.safety = safety;
    }

    /** Who is part-way through going somewhere — what a listener and a diagnostic ask. */
    public Departures pending() {
        return departures;
    }

    // ------------------------------------------------------------------------ setting off

    /**
     * Sends somebody somewhere, after however long the trip says.
     *
     * <p>With no warm-up they go at once. With one, they must stay on the block they are standing on
     * until it is up; {@link TravelListener} is what notices they have not.
     */
    public void go(Player traveller, Location destination, Trip trip, TravelWatcher watcher) {
        if (traveller == null || trip == null) {
            return;
        }
        UUID who = traveller.getUniqueId();

        // One journey at a time, and the check is the entry. Two instant trips issued in the same
        // tick used to both go through — the second finished after the first, so the player ended up
        // wherever the slower one pointed, and any caller charging its cooldown on arrival never saw
        // the first one in progress at all.
        if (!inFlight.add(who)) {
            (watcher == null ? new TravelWatcher() {
            } : watcher).refused(traveller, TravelReason.ALREADY_TRAVELLING, trip);
            return;
        }
        // Wrapped so that every way out of a journey — arriving, being cancelled, being refused —
        // takes the player out of the set. A journey that ends without doing so is a player who can
        // never travel again until they log out.
        TravelWatcher told = new Finishing(who, watcher == null ? new TravelWatcher() {
        } : watcher);

        if (destination == null || destination.getWorld() == null) {
            // Not an error and not a reason to delete anything: a multiverse server unloads worlds
            // for maintenance, and the place works again when the world comes back.
            told.refused(traveller, TravelReason.WORLD_MISSING, trip);
            return;
        }
        if (!trip.hasWarmup()) {
            arrive(traveller, destination, trip, told);
            return;
        }

        Spot standingOn = spotOf(traveller.getLocation());
        if (departures.begin(who, standingOn, trip.warmupSeconds(), trip.what()).isEmpty()) {
            // Belt and braces on the set above, and cheap. A departure that could not be begun is
            // one somebody else is already waiting out.
            told.refused(traveller, TravelReason.ALREADY_TRAVELLING, trip);
            return;
        }
        told.counting(traveller, trip.warmupSeconds(), trip);

        // The player's own scheduler: on Folia that is the region thread that owns them, and it
        // follows them if they cross into another region while they wait.
        ScheduledTask countdown = Scheduling.entityTimer(plugin, traveller, A_SECOND_IN_TICKS,
                A_SECOND_IN_TICKS, task -> onASecondPassing(traveller, task),
                // Retired: the player logged out or died mid-wait and the scheduler dropped the
                // task. Without this the departure stays in the map for ever.
                () -> forget(traveller.getUniqueId()));

        if (countdown == null) {
            // Nothing to count with — the plugin is being disabled, most likely. Better to refuse
            // than to leave somebody standing still for a teleport that will never come.
            departures.cancel(who);
            told.refused(traveller, TravelReason.CANNOT_SCHEDULE, trip);
            return;
        }
        journeys.put(who, new Journey(destination.clone(), trip, told, countdown));

        // The window this closes: between begin() above and the put() on the line before, a movement
        // or a quit on another thread finds the departure and cancels it, but finds no journey — so
        // the task is not cancelled and the player is never told. Without this the task survives up
        // to a second before noticing, and the person standing still is told nothing at all.
        if (!departures.isLeaving(who)) {
            Journey orphan = journeys.remove(who);
            if (orphan != null) {
                orphan.countdown().cancel();
                orphan.watcher().cancelled(traveller, TravelReason.MOVED, trip);
            }
        }
    }

    private void onASecondPassing(Player traveller, ScheduledTask task) {
        UUID who = traveller.getUniqueId();
        Journey journey = journeys.get(who);
        if (journey == null || !traveller.isOnline()) {
            task.cancel();
            forget(who);
            return;
        }
        switch (departures.tick(who)) {
            case WAITING -> departures.pending(who).ifPresent(left ->
                    journey.watcher().counting(traveller, left.secondsLeft(), journey.trip()));
            case ARRIVED -> {
                task.cancel();
                journeys.remove(who);
                arrive(traveller, journey.destination(), journey.trip(), journey.watcher());
            }
            // A stray tick from a task that was already cancelled. Nothing to do but stop.
            case NOTHING_PENDING -> {
                task.cancel();
                journeys.remove(who);
            }
        }
    }

    // ------------------------------------------------------------------------ giving up

    /**
     * Gives up on somebody's warm-up and tells them why.
     *
     * @return true when there was one to give up on, so a caller can stay quiet when there was not
     */
    public boolean cancel(Player traveller, TravelReason why) {
        if (traveller == null) {
            return false;
        }
        UUID who = traveller.getUniqueId();
        if (!departures.cancel(who)) {
            // Nothing to give up on. Deliberately not clearing the in-flight mark here: a teleport
            // already under way is not something a movement event can call off, and clearing it
            // would let a second command through while the first is still in the air.
            return false;
        }
        Journey journey = journeys.remove(who);
        if (journey != null) {
            journey.countdown().cancel();
            // The watcher is the wrapped one, so this is also what lets go of the in-flight mark.
            journey.watcher().cancelled(traveller, why, journey.trip());
        } else {
            inFlight.remove(who);
        }
        return true;
    }

    /**
     * Whether this player is part-way through going somewhere.
     *
     * <p>The set rather than the departures, so a trip with no warm-up counts too — for the caller
     * charging a cooldown when somebody arrives, an instant trip in progress is exactly the state
     * that must not look idle.
     */
    public boolean isTravelling(UUID traveller) {
        return traveller != null && inFlight.contains(traveller);
    }

    /** Where they were standing when they asked, for a listener working out whether they moved. */
    public Optional<Spot> setOffFrom(UUID traveller) {
        return departures.pending(traveller).map(Departure::from);
    }

    /** Forgets a player without telling them anything. Called when they log out. */
    public void forget(UUID traveller) {
        departures.cancel(traveller);
        inFlight.remove(traveller);
        Journey journey = journeys.remove(traveller);
        if (journey != null) {
            journey.countdown().cancel();
        }
    }

    /** Drops every warm-up. For a plugin being disabled. */
    public void clear() {
        journeys.values().forEach(journey -> journey.countdown().cancel());
        journeys.clear();
        departures.clear();
        inFlight.clear();
    }

    /**
     * A watcher that lets go of the player once their journey is over, however it ended.
     *
     * <p>Here rather than at each call site because there are five ways out of {@code go} and the one
     * that forgets to release is a player who can never travel again until they log out.
     */
    private final class Finishing implements TravelWatcher {

        private final UUID who;
        private final TravelWatcher told;

        private Finishing(UUID who, TravelWatcher told) {
            this.who = who;
            this.told = told;
        }

        @Override
        public void counting(Player traveller, int secondsLeft, Trip trip) {
            told.counting(traveller, secondsLeft, trip);
        }

        @Override
        public void arrived(Player traveller, Location where, Trip trip) {
            inFlight.remove(who);
            told.arrived(traveller, where, trip);
        }

        @Override
        public void cancelled(Player traveller, TravelReason why, Trip trip) {
            inFlight.remove(who);
            told.cancelled(traveller, why, trip);
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            inFlight.remove(who);
            told.refused(traveller, why, trip);
        }
    }

    // ------------------------------------------------------------------------ arriving

    /**
     * The teleport itself.
     *
     * <p>Safe arrival first when the trip asks for one, and a refusal when nowhere within the radius
     * is safe. Deliberately not "fall back to the exact spot": that puts the player in the place
     * already known to be dangerous, which is the whole thing the safety package exists to stop.
     */
    private void arrive(Player traveller, Location destination, Trip trip, TravelWatcher watcher) {
        if (!trip.safeArrival() || safety == null) {
            teleport(traveller, destination, trip, watcher);
            return;
        }
        Spot around = spotOf(destination);
        safety.findSafe(around, trip.searchRadius()).thenAccept(found ->
                // The answer arrives on whichever thread finished the chunk load. Everything below
                // touches the player, so hop back onto them first.
                onThePlayersThread(traveller, "arriving at " + trip.what(), () -> {
                    if (!traveller.isOnline()) {
                        return;
                    }
                    found.ifPresentOrElse(
                            spot -> teleport(traveller, at(spot, destination), trip, watcher),
                            () -> watcher.refused(traveller, TravelReason.NOWHERE_SAFE, trip));
                })).exceptionally(failure -> {
            log.warn("Could not check whether {} is safe: {}", around, failure.toString());
            onThePlayersThread(traveller, "refusing " + trip.what(), () ->
                    watcher.refused(traveller, TravelReason.COULD_NOT_CHECK, trip));
            return null;
        });
    }

    /**
     * Runs something on the thread that owns this player, and logs whatever it throws.
     *
     * <p>The {@code exceptionally} on the future above does <em>not</em> cover this: the work is
     * handed to a scheduler and runs later, on another thread, long after that stage has completed
     * successfully. Anything a watcher throws — a plugin's own message code, most likely — would
     * surface as an uncaught exception on the entity task looper, where on Folia it can take the
     * region's task queue with it.
     *
     * <p>Caught and logged with what it was doing, because the alternative is a stack trace naming
     * nothing but the scheduler.
     */
    private void onThePlayersThread(Player traveller, String what, Runnable task) {
        Scheduling.entity(plugin, traveller, () -> {
            try {
                task.run();
            } catch (RuntimeException thrown) {
                log.warn("{} failed while {}: {}", traveller.getName(), what, thrown.toString());
            }
        });
    }

    private void teleport(Player traveller, Location destination, Trip trip,
                          TravelWatcher watcher) {
        // Gathered before the player is moved, because afterwards there is nothing standing near them
        // to gather: the dog is still where they were, and they are not there any more.
        List<Entity> travellingWith = companionsOf(traveller, trip);

        // Async rather than a region-scheduled synchronous teleport: this is the one that handles
        // another plugin refusing the move — a world border, a closed dimension — without the
        // player being told twice that they have arrived somewhere they have not.
        traveller.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                .whenComplete((moved, failure) -> onThePlayersThread(traveller,
                        "arriving at " + trip.what(), () -> {
                    if (failure != null) {
                        log.warn("Could not send {} to {}: {}", traveller.getName(), trip.what(),
                                failure.toString());
                        watcher.refused(traveller, TravelReason.TELEPORT_REFUSED, trip);
                        return;
                    }
                    if (Boolean.FALSE.equals(moved)) {
                        watcher.refused(traveller, TravelReason.TELEPORT_REFUSED, trip);
                        return;
                    }
                    bring(travellingWith, destination, trip);
                    watcher.arrived(traveller, destination, trip);
                }));
    }

    // ------------------------------------------------------------------------ what comes along

    /**
     * The entities that travel with this player.
     *
     * <p>Read off the running server and handed to {@link Entourage}, which owns the decision and is
     * tested without one. Nothing here decides anything: it converts what Bukkit knows into the plain
     * facts the rule is written against.
     *
     * <p>The vehicle they are riding, and whatever is riding with them, are reported as things they
     * are <em>leading</em>. From the traveller's point of view a boat they are towing and a boat they
     * are sitting in are the same "I am taking this with me", and treating them differently would mean
     * two villagers in a boat came along only when the boat was on a lead.
     */
    private List<Entity> companionsOf(Player traveller, Trip trip) {
        Entourage entourage = new Entourage(trip.companions());
        if (!entourage.isWorthLooking()) {
            return List.of();
        }
        UUID who = traveller.getUniqueId();
        Map<UUID, Entity> byId = new LinkedHashMap<>();
        List<Entourage.Candidate> around = new ArrayList<>();

        // The vehicle and its other passengers, however far the seat is from the eyes.
        Entity vehicle = traveller.getVehicle();
        if (vehicle != null) {
            // The vehicle only. Its other passengers are not considered separately: Paper carries
            // them with it, and teleporting a passenger in its own right is what throws it out of the
            // boat on arrival. Whether the vehicle may travel at all is then one decision — and a
            // vehicle carrying somebody else never may.
            consider(vehicle, who, 0, true, byId, around);
        }
        // Whatever is riding on the traveller themselves — a parrot, usually. They arrive with the
        // player for the same reason, so this is only here to keep them out of the nearby scan below.
        for (Entity onTheirShoulder : traveller.getPassengers()) {
            byId.put(onTheirShoulder.getUniqueId(), onTheirShoulder);
        }

        // Then everything within reach. The radius is the policy's, and the lead is not limited by it
        // — but something on a lead is by definition within a lead's length, which is shorter.
        int radius = Math.max(trip.companions().radius(), A_LEADS_LENGTH);
        Location standingAt = traveller.getLocation();
        for (Entity nearby : traveller.getNearbyEntities(radius, radius, radius)) {
            // Anything riding something else is left to its vehicle: teleporting a passenger in its
            // own right dismounts it on arrival, which is how two villagers in a towed boat end up
            // swimming.
            if (nearby.getVehicle() != null) {
                continue;
            }
            consider(nearby, who, blocksBetween(standingAt, nearby.getLocation()), false, byId,
                    around);
        }

        List<Entity> coming = new ArrayList<>();
        for (Entourage.Candidate chosen : entourage.from(around, who)) {
            Entity entity = byId.get(chosen.entity());
            if (entity != null) {
                coming.add(entity);
            }
        }
        return coming;
    }

    /**
     * How far apart two places are, in whole blocks.
     *
     * <p>Squared and compared rather than {@code distance()}, which throws when the two are in
     * different worlds. They should not be — {@code getNearbyEntities} only ever returns entities from
     * the player's own world — but "should not be" is how a warp comes to fail with an
     * IllegalArgumentException in somebody's log.
     */
    private static int blocksBetween(Location from, Location to) {
        if (from.getWorld() == null || to.getWorld() == null
                || !from.getWorld().equals(to.getWorld())) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(Math.sqrt(from.distanceSquared(to)));
    }

    /**
     * How long a lead is, at most, so the search cannot be narrower than one.
     *
     * <p>A vanilla lead snaps at ten blocks. Searching only the policy's radius would leave a boat
     * trailing at full length behind, which is exactly the case the feature exists for.
     */
    private static final int A_LEADS_LENGTH = 10;

    private void consider(Entity entity, UUID traveller, int blocksAway, boolean riding,
                          Map<UUID, Entity> byId, List<Entourage.Candidate> around) {
        if (entity == null || byId.containsKey(entity.getUniqueId())) {
            return;
        }
        try {
            boolean isPlayer = entity instanceof Player;
            UUID leadHeldBy = leadHolderOf(entity, traveller, riding);
            UUID tamedBy = entity instanceof Tameable tameable && tameable.isTamed()
                            && tameable.getOwner() != null
                    ? tameable.getOwner().getUniqueId()
                    : null;
            boolean isTame = tamedBy != null;
            boolean carriesAPlayer = carriesAPlayer(entity);

            byId.put(entity.getUniqueId(), entity);
            around.add(new Entourage.Candidate(entity.getUniqueId(), isPlayer, leadHeldBy, tamedBy,
                    blocksAway, isTame, carriesAPlayer));
        } catch (RuntimeException unreadable) {
            // On Folia an entity a few blocks away can belong to another region thread, and reading
            // it from this one is refused rather than merely wrong. Skipped, not fatal: the cost of
            // guessing is a dog left behind, and the cost of throwing is a warp that fails.
            log.warn("Could not read {} to see whether it travels: {}",
                    entity.getType(), unreadable.toString());
        }
    }

    /**
     * Whether somebody is sitting in or on this — at any depth.
     *
     * <p>Recursive because a passenger may itself carry one: a player on a donkey in a boat. Paper
     * moves the whole stack with the vehicle, so a check that only looked one level down would let a
     * towed boat teleport a player after all.
     */
    private static boolean carriesAPlayer(Entity entity) {
        for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof Player || carriesAPlayer(passenger)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Who is holding this entity's lead, as far as travelling is concerned.
     *
     * @param riding whether it came from the vehicle or passenger list, which counts as led
     */
    private UUID leadHolderOf(Entity entity, UUID traveller, boolean riding) {
        if (riding) {
            return traveller;
        }
        if (entity instanceof LivingEntity living && living.isLeashed()) {
            Entity holder = living.getLeashHolder();
            return holder == null ? null : holder.getUniqueId();
        }
        return null;
    }

    /**
     * Moves whatever is coming along, after the player has arrived.
     *
     * <p>Each on its own scheduler, and each failure logged rather than thrown: on Folia every one of
     * these may belong to a different region, and one animal that cannot be moved must not stop the
     * rest — or, worse, leave the player standing at the destination with nothing and no explanation.
     *
     * <p>Nothing is un-leashed or dismounted first. Paper carries a passenger with its vehicle and
     * re-attaches a lead across a teleport; doing it by hand was tried and produced a dog standing
     * still with a lead stretched across two worlds.
     */
    private void bring(List<Entity> travellingWith, Location destination, Trip trip) {
        int placed = 0;
        for (Entity companion : travellingWith) {
            // Spread around the arrival rather than stacked in one block. Twenty animals in one
            // block is entity cramming, which suffocates them — a feature that brings the dog and
            // then kills it is worse than one that leaves it behind.
            Location spot = besideTheArrival(destination, placed++, travellingWith.size());
            Scheduling.entity(plugin, companion, () -> {
                try {
                    if (spot.getWorld() == null || !spot.isWorldLoaded()) {
                        // The world went between the player's arrival and this task. Nothing to do
                        // but say so: the animal stays where it was, which is recoverable.
                        log.warn("Could not bring {} to {}: that world is gone.",
                                companion.getType(), trip.what());
                        return;
                    }
                    companion.teleportAsync(spot, PlayerTeleportEvent.TeleportCause.PLUGIN);
                } catch (RuntimeException thrown) {
                    log.warn("Could not bring {} to {}: {}", companion.getType(), trip.what(),
                            thrown.toString());
                }
            });
        }
    }

    /**
     * Somewhere within a block or so of the arrival, for one of several companions.
     *
     * <p>A ring rather than a line, so a boat and a horse do not end up inside each other, and small
     * enough that nothing lands through a wall — the destination has already been checked for safety
     * and a metre away has not, so this stays inside the same block wherever it can.
     */
    private static Location besideTheArrival(Location arrival, int which, int howMany) {
        if (howMany <= 1) {
            return arrival;
        }
        double angle = 2 * Math.PI * which / howMany;
        return arrival.clone().add(Math.cos(angle) * 0.8, 0, Math.sin(angle) * 0.8);
    }

    // ------------------------------------------------------------------------ the two conversions

    /** Where a player's feet are, as the value the rules are written against. */
    public static Spot spotOf(Location where) {
        if (where == null || where.getWorld() == null) {
            return null;
        }
        return new Spot(where.getWorld().getName(), where.getBlockX(), where.getBlockY(),
                where.getBlockZ());
    }

    /**
     * A safe spot, as somewhere to be put.
     *
     * <p>The middle of the block, not its corner — a player put on the corner is a player half
     * inside the block next to it. Facing whichever way the original destination faced, so a warp
     * nudged two blocks by the safety check still looks at what it was pointed at.
     */
    private static Location at(Spot spot, Location facingLike) {
        World world = org.bukkit.Bukkit.getWorld(spot.world());
        if (world == null) {
            return facingLike;
        }
        return new Location(world, spot.centreX(), spot.y(), spot.centreZ(),
                facingLike.getYaw(), facingLike.getPitch());
    }
}
