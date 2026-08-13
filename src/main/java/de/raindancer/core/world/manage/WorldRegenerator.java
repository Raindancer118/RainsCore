package de.raindancer.core.world.manage;

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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Throws a world's folder away and makes the same name again from scratch — the generic version of
 * what {@code SpeedrunReset} in the speedrun module does for one particular map.
 *
 * <h2>Why no seed is ever set</h2>
 * Asked for explicitly: this is a plain wipe, not a "restore this exact map" tool. A fixed seed would
 * make every regen of a given world identical to the last, which is only occasionally what somebody
 * running {@code /world regen} wants and is surprising the rest of the time. So the new
 * {@link WorldCreator} is handed nothing but the name, and whatever seed Bukkit picks for a from-scratch
 * world is whatever comes back — the same as creating a brand new world by hand.
 *
 * <h2>Order of operations, and why it is fixed</h2>
 * Evacuate whoever is standing in it, unload without saving, delete the folder deepest-first, then
 * create again. Each step exists for the same reason {@code FarmWorlds}' own regeneration does:
 * teleporting after the unload would fail (the world is gone), saving before deleting would spend time
 * writing chunks that are about to be thrown away, and deleting before the world is unloaded fights the
 * OS over files the server still has open.
 *
 * <h2>Threading</h2>
 * Main thread only — creating, unloading and deleting a world are main-thread operations in Paper.
 */
public final class WorldRegenerator {

    private static final LogChannel log = Log.of("world");

    /**
     * Deletes {@code world}'s folder and recreates it, empty, under the same name.
     *
     * @param world the world to throw away; must still be loaded, so its real folder can be read from
     *              it before anything happens to it — see {@code SpeedrunReset} for why resolving the
     *              folder from the world container by name alone is a bug, not a shortcut
     * @return the freshly created world, or empty if the regeneration could not be completed — the
     *         server log has the reason either way
     */
    public boolean regenerate(World world) {
        if (world == null) {
            return false;
        }
        String name = world.getName();
        Path folder = world.getWorldFolder().toPath();

        Location safety = safeSpawn();
        if (safety == null) {
            log.error("Cannot regenerate '{}': there is nowhere to move players to.", name);
            return false;
        }
        evacuate(world, safety);
        if (!Bukkit.unloadWorld(world, false)) {
            log.error("Could not unload '{}', so the regeneration was abandoned.", name);
            return false;
        }
        if (!deleteFolder(folder, name)) {
            log.fatal("'{}' was only partly deleted and has NOT been made again. Its folder is at "
                    + "{} — remove it by hand.", name, folder);
            return false;
        }
        World recreated = new WorldCreator(name).createWorld();
        if (recreated == null) {
            log.error("The server would not create the world '{}' again.", name);
            return false;
        }
        log.info("World '{}' has been regenerated.", name);
        return true;
    }

    /** Moves everybody currently in {@code world} out of it, before it is unloaded from under them. */
    private void evacuate(World world, Location safety) {
        for (Player player : List.copyOf(world.getPlayers())) {
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
