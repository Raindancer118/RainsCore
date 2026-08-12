package de.raindancer.core.world.speedrun;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Throws a world away and makes it again, for a fresh attempt at a run.
 *
 * <h2>Why this does not use {@code FarmWorlds}</h2>
 * The steps are the same — evacuate, unload without saving, delete deepest-first, recreate — because
 * they are the only safe order there is (see {@code FarmWorlds}'s javadoc for why each one exists).
 * But {@code FarmWorlds}'s version is wired into {@code WorldSet} and {@code FarmWorldState}: which
 * worlds belong to a set, whether a folder may be deleted at all, when a set is due. None of that
 * applies to a speedrun map, which is one world with no bookkeeping of its own, so this is a small
 * self-contained copy of the pattern rather than a reuse of the machinery around it.
 *
 * <h2>Threading</h2>
 * Main thread only, exactly like {@code FarmWorlds.regenerate} — creating, unloading and deleting a
 * world are main-thread operations in Paper.
 */
public final class SpeedrunReset {

    private static final LogChannel log = Log.of("speedrun");

    /**
     * Deletes {@code world}'s folder and recreates it under the same name.
     *
     * @param world    the world to throw away; must still be loaded, so its real folder can be read
     *                 from it before anything happens to it
     * @param seed     what seed policy the new world gets
     * @param evacuate who to move out first — moved to the first loaded world's spawn, the same
     *                 fallback {@code FarmWorlds} uses when there is nowhere more specific to send
     *                 somebody
     * @return whether the world came back
     */
    public boolean regenerate(World world, SpeedrunSeed seed, Collection<UUID> evacuate) {
        if (world == null || seed == null) {
            return false;
        }
        String name = world.getName();
        // Read from the World itself, and read *now* — before the unload, while there is still a
        // World to ask. Resolving the folder from Bukkit.getWorldContainer() by name alone is a bug
        // that was already found and fixed for FarmWorlds: Paper 26 puts non-primary worlds under
        // <level-name>/dimensions/<namespace>/<name>, not directly under the world container.
        Path folder = world.getWorldFolder().toPath();

        Location safety = safeSpawn();
        if (safety == null) {
            log.error("Cannot regenerate '{}': there is nowhere to move players to.", name);
            return false;
        }
        evacuate(world, evacuate, safety);
        // save = false: writing chunks to disk immediately before deleting them is a freeze that buys
        // nothing.
        if (!Bukkit.unloadWorld(world, false)) {
            log.error("Could not unload '{}', so the reset was abandoned.", name);
            return false;
        }
        if (!deleteFolder(folder, name)) {
            log.fatal("'{}' was only partly deleted and has NOT been made again. Its folder is at "
                    + "{} — remove it by hand.", name, folder);
            return false;
        }
        World recreated = seed.apply(new WorldCreator(name)).createWorld();
        if (recreated == null) {
            log.error("The server would not create the world '{}' again.", name);
            return false;
        }
        log.info("Speedrun world '{}' has been reset.", name);
        return true;
    }

    /**
     * Moves the given players out of {@code world}, ignoring anybody not currently in it.
     *
     * <p>{@code teleportAsync}, not {@code teleport}: on Folia a synchronous teleport across regions
     * throws, and the world would then be unloaded with somebody still in it — the same reasoning as
     * {@code FarmWorlds.evacuate}. This does not wait for the teleports either, for the same reason:
     * a wait here would block the very thread the teleport needs to complete on.
     */
    private void evacuate(World world, Collection<UUID> evacuate, Location safety) {
        if (evacuate == null) {
            return;
        }
        for (UUID id : evacuate) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !world.equals(player.getWorld())) {
                continue;
            }
            try {
                player.teleportAsync(safety);
            } catch (RuntimeException failure) {
                log.warn(failure, "Could not move {} out of '{}'.", player.getName(), world.getName());
            }
        }
    }

    /** The first loaded world's spawn — the safe fallback when there is nowhere more specific to send somebody. */
    private static Location safeSpawn() {
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.getFirst().getSpawnLocation();
    }

    /** Deletes a folder deepest-first, because a directory cannot be removed until it is empty. */
    private boolean deleteFolder(Path folder, String name) {
        if (folder == null || !Files.exists(folder)) {
            return true;
        }
        try (Stream<Path> contents = Files.walk(folder)) {
            List<Path> deepestFirst = contents.sorted(Comparator.reverseOrder()).toList();
            for (Path each : deepestFirst) {
                Files.deleteIfExists(each);
            }
            return true;
        } catch (IOException failure) {
            log.error(failure, "Could not delete '{}'.", name);
            return false;
        }
    }
}
