package de.raindancer.core.world.farm;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Farm worlds, as the server has them: created, linked, and thrown away when their time is up.
 *
 * <h2>What is here and what is not</h2>
 * Only what needs a server. {@link WorldSet} decides what the worlds are called, which belong
 * together, where a portal in one should lead and when the set is due; {@link FarmWorldState}
 * remembers all that and — critically — decides what may be deleted. This does the doing.
 *
 * <h2>Regenerating, in order</h2>
 * The order is the whole of it, and each step exists because skipping it breaks something:
 * <ol>
 *   <li><b>Move everybody out</b>, to the main world's spawn. A player left in a world being
 *       unloaded is a player in a world that no longer exists.</li>
 *   <li><b>Unload, saving nothing.</b> Saving a world that is about to be deleted writes chunks to
 *       disk for the pleasure of deleting them a moment later, and on a large farm world that is a
 *       visible freeze.</li>
 *   <li><b>Delete the folder</b>, and only after {@link FarmWorldState#mayDelete} has agreed.</li>
 *   <li><b>Create it again</b>, with a new seed.</li>
 * </ol>
 * If any step fails, the ones after it do not run. A half-regenerated farm world is recoverable; a
 * deleted-but-not-recreated one is a server with a hole in it.
 *
 * <h2>Threading</h2>
 * Creating, unloading and deleting a world are main-thread operations in Paper and are not safe
 * anywhere else, so everything here expects to be on it. That is also why regeneration is something
 * a server owner schedules for a quiet hour rather than something that happens mid-fight: it stops
 * the server for as long as the disk takes.
 */
public final class FarmWorlds {

    private static final LogChannel log = Log.of("worlds");

    /** How long to wait for a player to actually leave before unloading the world anyway. */
    private static final long EVACUATION_SECONDS = 5;

    private final Plugin plugin;
    private final FarmWorldState state;

    public FarmWorlds(Plugin plugin, FarmWorldState state) {
        this.plugin = plugin;
        this.state = state;
    }

    public FarmWorldState state() {
        return state;
    }

    // ---------------------------------------------------------------------------- creating

    /**
     * Loads a set's worlds, creating any that are not there.
     *
     * <p>Called at startup for every set. A world that already exists is loaded as it is — this is
     * not regeneration and must never quietly become it.
     *
     * @return the worlds that are now loaded
     */
    public List<World> ensure(WorldSet set) {
        List<World> loaded = new ArrayList<>(3);
        for (String name : set.worlds()) {
            World world = Bukkit.getWorld(name);
            if (world == null) {
                world = create(set, name);
            }
            if (world != null) {
                loaded.add(world);
            }
        }
        if (state.lastRegenerated(set.name()).isEmpty() && !loaded.isEmpty()) {
            // First time it has existed: the schedule counts from now rather than from the epoch,
            // which would make a brand-new farm world immediately due.
            state.recordRegenerated(set.name(), Instant.now());
        }
        return List.copyOf(loaded);
    }

    private World create(WorldSet set, String name) {
        WorldCreator creator = new WorldCreator(name)
                .environment(environmentOf(set, name))
                .seed(set.nextSeed());
        try {
            World world = creator.createWorld();
            if (world == null) {
                log.error("The server would not create the world '{}'.", name);
                return null;
            }
            set.border().ifPresent(radius -> {
                world.getWorldBorder().setCenter(0, 0);
                // A border is given as a radius because that is how somebody thinks about it; Bukkit
                // wants the full width.
                world.getWorldBorder().setSize(radius * 2.0);
            });
            log.info("Farm world '{}' is ready.", name);
            return world;
        } catch (RuntimeException failure) {
            log.error(failure, "Could not create the world '{}'.", name);
            return null;
        }
    }

    private static World.Environment environmentOf(WorldSet set, String name) {
        return set.partOf(name).map(part -> switch (part) {
            case OVERWORLD -> World.Environment.NORMAL;
            case NETHER -> World.Environment.NETHER;
            case END -> World.Environment.THE_END;
        }).orElse(World.Environment.NORMAL);
    }

    // ---------------------------------------------------------------------------- regenerating

    /**
     * Throws a set's worlds away and makes them again.
     *
     * <p>Main thread only. Stops the server for as long as the disk takes, which is why it is
     * something to schedule rather than something to do while people are playing.
     *
     * @return whether every world came back
     */
    public boolean regenerate(WorldSet set) {
        log.info("Regenerating the farm world '{}'.", set.name());
        Location safety = safeSpawn();
        if (safety == null) {
            // Nowhere to put the players. Better a stale farm world than players in a world that is
            // about to stop existing.
            log.error("Cannot regenerate '{}': there is nowhere to move players to.", set.name());
            return false;
        }

        boolean allBack = true;
        for (String name : set.worlds()) {
            if (!regenerateOne(set, name, safety)) {
                allBack = false;
            }
        }
        Instant now = Instant.now();
        // The attempt is always recorded, so a set that cannot be made does not retry every minute.
        state.recordAttempt(set.name(), now);
        if (allBack) {
            state.recordRegenerated(set.name(), now);
        } else {
            // Deliberately not recorded as regenerated. Doing so — which this used to — reset the
            // schedule as though it had worked, and left a depleted farm world depleted for the
            // whole period with nothing further in the log.
            log.error("'{}' was not fully made again. It stays due, and will be tried again in {}.",
                    set.name(),
                    de.raindancer.core.moderation.punishment.Durations.describe(FarmWorldState.RETRY_AFTER));
        }
        state.flush();
        return allBack;
    }

    private boolean regenerateOne(WorldSet set, String name, Location safety) {
        World world = Bukkit.getWorld(name);
        if (world != null) {
            evacuate(world, safety);
            // save = false: writing chunks to disk immediately before deleting them is a freeze
            // that buys nothing.
            if (!Bukkit.unloadWorld(world, false)) {
                log.error("Could not unload '{}', so it was left alone rather than half-removed.",
                        name);
                return false;
            }
        }
        Path folder = Bukkit.getWorldContainer().toPath().resolve(name);
        if (Files.exists(folder) && !deleteWorldFolder(folder, name)) {
            // Deliberately NOT recreated. A half-deleted folder is the one case where making the
            // world again is worse than not having it: WorldCreator would generate fresh terrain
            // with a new seed around whatever region files survived, and the result is permanent
            // chunk walls through the middle of the world. A world that is simply missing can be
            // fixed by hand; a corrupted one cannot.
            log.fatal("'{}' was only partly deleted and has NOT been made again. Its folder is at "
                    + "{} — remove it by hand, then start the server. Recreating it now would "
                    + "generate new terrain around the surviving chunks.", name, folder);
            return false;
        }
        return create(set, name) != null;
    }

    /** Moves everybody out of a world before it stops existing. */
    private void evacuate(World world, Location safety) {
        List<Player> inside = List.copyOf(world.getPlayers());
        List<java.util.concurrent.CompletableFuture<Boolean>> moving =
                new ArrayList<>(inside.size());
        for (Player player : inside) {
            try {
                // teleportAsync, not teleport: on Folia a synchronous teleport across regions
                // throws, and the world would then be unloaded with somebody still in it.
                moving.add(player.teleportAsync(safety));
                player.sendMessage(net.kyori.adventure.text.Component.text(
                        "The farm world is being made again — you have been moved to spawn."));
            } catch (RuntimeException failure) {
                log.warn(failure, "Could not move {} out of '{}'.", player.getName(),
                        world.getName());
            }
        }
        // Waited for, because the next thing that happens is the world being unloaded. A player
        // still in flight when that happens is a player in a world that no longer exists.
        for (var move : moving) {
            try {
                move.get(EVACUATION_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception slow) {
                log.warn("A player did not leave '{}' in time; carrying on.", world.getName());
            }
        }
    }

    /** The main world's spawn, or null when there is not one. */
    private Location safeSpawn() {
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.getFirst().getSpawnLocation();
    }

    /**
     * Deletes a world folder, once {@link FarmWorldState#mayDelete} has agreed it is ours.
     *
     * <p>The check is separate, pure and heavily tested for a reason: this is the one operation in
     * the library that cannot be undone.
     */
    private boolean deleteWorldFolder(Path folder, String name) {
        Path serverDirectory = Bukkit.getWorldContainer().toPath();
        if (!FarmWorldState.mayDelete(serverDirectory, folder, name)) {
            log.error("Refusing to delete '{}': it is not a farm world folder of ours.", folder);
            return false;
        }
        try (Stream<Path> contents = Files.walk(folder)) {
            // Deepest first, because a directory cannot be removed until it is empty.
            List<Path> deepestFirst = contents
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path each : deepestFirst) {
                Files.deleteIfExists(each);
            }
            return true;
        } catch (IOException failure) {
            log.error(failure, "Could not delete '{}'.", folder);
            return false;
        }
    }

    // ---------------------------------------------------------------------------- the schedule

    /**
     * Regenerates every set whose time is up.
     *
     * <p>Called from a slow timer. Deliberately does at most one per call: two farm worlds coming
     * due in the same hour should be two pauses, not one long one.
     */
    public void regenerateWhatIsDue() {
        List<WorldSet> due = state.due(Instant.now());
        if (due.isEmpty()) {
            return;
        }
        regenerate(due.getFirst());
    }

    // ---------------------------------------------------------------------------- portals

    /**
     * Where somebody stepping through a portal in a farm world should come out.
     *
     * <p>The reason a farm world has its own nether at all: without this, a portal in the farm world
     * leads to the <em>main</em> nether, and the farm world protects nothing. Empty when the portal
     * is not in one of our worlds, which leaves every other portal on the server alone.
     *
     * @param from  where the portal is
     * @param to    which kind of world it leads to
     */
    public Optional<Location> portalTarget(Location from, WorldSet.Part to) {
        if (from == null || from.getWorld() == null) {
            return Optional.empty();
        }
        String fromWorld = from.getWorld().getName();
        Optional<WorldSet> owning = state.setOwning(fromWorld);
        if (owning.isEmpty()) {
            return Optional.empty();
        }
        WorldSet set = owning.get();
        Optional<String> targetName = set.portalTarget(fromWorld, to);
        if (targetName.isEmpty()) {
            return Optional.empty();
        }
        World target = Bukkit.getWorld(targetName.get());
        if (target == null) {
            log.warn("'{}' should lead to '{}', which is not loaded.", fromWorld, targetName.get());
            return Optional.empty();
        }
        WorldSet.Part fromPart = set.partOf(fromWorld).orElse(WorldSet.Part.OVERWORLD);
        return Optional.of(new Location(target,
                WorldSet.scaleCoordinate(from.getX(), fromPart, to),
                from.getY(),
                WorldSet.scaleCoordinate(from.getZ(), fromPart, to)));
    }
}
