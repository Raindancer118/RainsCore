package de.raindancer.core.world.manage;

import de.raindancer.core.RainsCore;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Creating, deleting and regenerating a world, generically — the one place any of the three has to be
 * gotten right, so a module needing "make sure this world exists" or "wipe this one for a fresh
 * attempt" reaches for this instead of writing its own copy of unload-then-delete-then-recreate.
 *
 * <h2>Why no seed is ever set</h2>
 * Asked for explicitly: this is a plain wipe, not a "restore this exact map" tool. A fixed seed would
 * make every regen of a given world identical to the last, which is only occasionally what somebody
 * running {@code /world regen} wants and is surprising the rest of the time. So the new
 * {@link WorldCreator} is handed nothing but the name, and whatever seed Bukkit picks for a from-scratch
 * world is whatever comes back — the same as creating a brand new world by hand.
 *
 * <h2>Order of operations, and why it is fixed</h2>
 * Evacuate whoever is standing in it — back to wherever {@link WorldEntryPoints} last saw them before
 * they arrived, not a generic spawn — wait for every one of those moves to actually land, unload
 * without saving, delete the folder deepest-first, then create again. Each step exists for a reason:
 * teleporting after the unload would fail (the world is gone), saving before deleting would spend time
 * writing chunks that are about to be thrown away, deleting before the world is unloaded fights the OS
 * over files the server still has open, and unloading before every occupant has actually left is
 * either refused by Bukkit outright or strands whoever's teleport had not yet landed.
 *
 * <h2>Why {@link #delete} and {@link #regenerate} take a callback</h2>
 * Waiting for occupants to actually arrive somewhere is asynchronous — {@link Player#teleportAsync}
 * is the only safe way to move someone across worlds at all, let alone on Folia — so there is no
 * synchronous answer to give back. A caller with nobody to evacuate still gets its answer on the same
 * tick; one with occupants gets it once their teleports resolve.
 *
 * <h2>Threading</h2>
 * Every callback runs on the main thread (a {@link Player#teleportAsync} future completes there), so
 * touching Bukkit API from inside one is safe. Everything else here — {@link #create}, and {@link #delete}
 * for an already-empty world — still requires being called from the main thread in the first place;
 * creating, unloading and deleting a world are main-thread-only operations in Paper regardless.
 */
public final class WorldRegenerator {

    private static final LogChannel log = Log.of("world");

    /**
     * Deletes {@code world}'s folder and recreates it, empty, under the same name — {@link #delete}
     * followed by {@link #create}, so the two share exactly one copy of each step's own rules (the
     * primary world refused, occupants evacuated first, Bukkit's own refusal handled).
     *
     * @param world    the world to throw away; must still be loaded, so its real folder can be read
     *                 from it before anything happens to it — resolving the folder from the world
     *                 container by name alone is a bug, not a shortcut, since Paper 26 nests a
     *                 non-primary world's folder under {@code <level-name>/dimensions/<namespace>/<name>}
     * @param whenDone told once, with whether the world came back — the server log has the reason
     *                 either way when it did not
     */
    public void regenerate(World world, Consumer<Boolean> whenDone) {
        if (world == null) {
            whenDone.accept(false);
            return;
        }
        String name = world.getName();
        delete(world, deleted -> {
            if (!deleted) {
                whenDone.accept(false);
                return;
            }
            whenDone.accept(create(name));
        });
    }

    /**
     * Unloads {@code world} and deletes its folder — no recreation. What {@link #regenerate} calls
     * first; also useful on its own for a caller that wants a name gone for good, not remade empty.
     *
     * <p>Whoever is standing in it is sent back to wherever they were right before they entered it —
     * see {@link WorldEntryPoints} — falling back to the first loaded world's spawn only when nothing
     * was ever recorded for them. Unlike {@link #evacuate}, this <em>waits</em> for every teleport to
     * actually land before unloading anything: a caller of this method gets one answer, not "try again
     * once they have left" for {@code evacuate}'s own callers to poll.
     *
     * @param whenDone told once, with whether it is gone — false leaves the world exactly as it was,
     *                 whatever the reason
     */
    public void delete(World world, Consumer<Boolean> whenDone) {
        if (world == null) {
            whenDone.accept(false);
            return;
        }
        String name = world.getName();
        if (isPrimaryWorld(world)) {
            // Not a transient failure worth retrying — Bukkit refuses this unconditionally, forever,
            // for the one world at index 0 of getWorlds() (Paper's own level-name world). Checked
            // before evacuating anybody, so a misconfigured caller finds out without moving players
            // for nothing.
            log.error("Cannot delete '{}': it is this server's primary world, which Bukkit never "
                    + "allows to be unloaded.", name);
            whenDone.accept(false);
            return;
        }
        Path folder = world.getWorldFolder().toPath();
        List<Player> occupants = List.copyOf(world.getPlayers());
        if (occupants.isEmpty()) {
            finishDelete(world, folder, name, whenDone);
            return;
        }
        List<CompletableFuture<Boolean>> moves = new ArrayList<>(occupants.size());
        for (Player player : occupants) {
            Location destination = destinationFor(player, world);
            if (destination == null) {
                log.error("Cannot delete '{}': there is nowhere to move {} to.", name, player.getName());
                whenDone.accept(false);
                return;
            }
            moves.add(player.teleportAsync(destination));
        }
        CompletableFuture.allOf(moves.toArray(CompletableFuture[]::new))
                .thenRun(() -> finishDelete(world, folder, name, whenDone))
                .exceptionally(failure -> {
                    log.error(failure, "Could not move everybody out of '{}', so the deletion was "
                            + "abandoned.", name);
                    whenDone.accept(false);
                    return null;
                });
    }

    private void finishDelete(World world, Path folder, String name, Consumer<Boolean> whenDone) {
        if (!Bukkit.unloadWorld(world, false)) {
            log.error("Could not unload '{}', so the deletion was abandoned.", name);
            whenDone.accept(false);
            return;
        }
        if (!deleteFolder(folder, name)) {
            log.fatal("'{}' was only partly deleted. Its folder is at {} — remove it by hand.",
                    name, folder);
            whenDone.accept(false);
            return;
        }
        log.info("World '{}' has been deleted.", name);
        whenDone.accept(true);
    }

    /**
     * Where to send {@code player} on their way out of {@code leaving} — wherever
     * {@link WorldEntryPoints} last saw them before they entered it, if that is still somewhere real
     * and not {@code leaving} itself, or the first loaded world's spawn otherwise.
     */
    private static Location destinationFor(Player player, World leaving) {
        Location remembered = RainsCore.get().worldEntryPoints().before(player.getUniqueId())
                .filter(at -> at.getWorld() != null && !at.getWorld().equals(leaving))
                .orElse(null);
        return remembered != null ? remembered : safeSpawn();
    }

    /**
     * Makes a brand new world called {@code name} from scratch, with whatever seed Bukkit picks —
     * see the class javadoc for why nothing here ever sets one. What {@link #regenerate} calls second;
     * also useful on its own for a caller that just wants a name to exist, nothing having been there
     * before.
     *
     * @return whether it now exists and is loaded
     */
    public boolean create(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (Bukkit.getWorld(name) != null) {
            log.warn("'{}' is already loaded; not creating it again.", name);
            return false;
        }
        World created = new WorldCreator(name).createWorld();
        if (created == null) {
            log.error("The server would not create the world '{}'.", name);
            return false;
        }
        log.info("World '{}' has been created.", name);
        return true;
    }

    /**
     * Whether {@code world} is the one at index 0 of {@link Bukkit#getWorlds()} — Paper's own
     * level-name world, which {@link Bukkit#unloadWorld} refuses unconditionally. Public so a caller
     * can warn about a configuration that names the primary world before ever attempting
     * {@link #delete}/{@link #regenerate}, rather than only finding out when one fails.
     */
    public static boolean isPrimaryWorld(World world) {
        List<World> worlds = Bukkit.getWorlds();
        return !worlds.isEmpty() && worlds.getFirst().equals(world);
    }

    /**
     * Moves everybody currently in {@code world} out of it, before it is unloaded from under them.
     *
     * <p>Shared with {@code FarmWorlds}, which needs the identical move — evacuate, then leave
     * unloading and deleting to the caller — for each world in a linked set, with its own message and
     * its own retry bookkeeping on top. This is the one place the actual teleport loop lives.
     *
     * @return whether anybody had to be moved at all — a caller must not unload or delete while this
     *         is true, since their teleport has only just started
     */
    public static boolean evacuate(World world, Location safety) {
        return evacuate(world, safety, player -> { });
    }

    /** The same, and also given each player before their teleport starts — for a caller with its own message. */
    public static boolean evacuate(World world, Location safety, Consumer<Player> notify) {
        List<Player> inside = List.copyOf(world.getPlayers());
        for (Player player : inside) {
            try {
                // teleportAsync, not teleport: on Folia a synchronous teleport across regions throws,
                // and the world would then be unloaded with somebody still in it.
                player.teleportAsync(safety);
                notify.accept(player);
            } catch (RuntimeException failure) {
                log.warn(failure, "Could not move {} out of '{}'.", player.getName(), world.getName());
            }
        }
        return !inside.isEmpty();
    }

    /** The first loaded world's spawn — the safe fallback when there is nowhere more specific to send somebody. */
    private static Location safeSpawn() {
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.getFirst().getSpawnLocation();
    }

    /**
     * Deletes a folder deepest-first, because a directory cannot be removed until it is empty.
     *
     * <p>Shared with {@code FarmWorlds}, which gates every call behind its own
     * {@code FarmWorldState#mayDelete} first — this does no ownership check of its own, and a caller
     * that skipped one would delete whatever path it was handed.
     */
    public static boolean deleteFolder(Path folder, String name) {
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
