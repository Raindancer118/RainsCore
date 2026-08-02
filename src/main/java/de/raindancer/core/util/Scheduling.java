package de.raindancer.core.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Thin wrapper around Paper's regionised schedulers.
 * <p>
 * Paper ships {@code RegionScheduler}, {@code AsyncScheduler}, {@code GlobalRegionScheduler} and
 * {@code Entity#getScheduler()} on both vanilla Paper and Folia, so using them exclusively keeps the
 * plugin Folia-safe without any runtime branching. {@code Bukkit.getScheduler()} is never touched.
 */
public final class Scheduling {

    private static final boolean FOLIA = classPresent("io.papermc.paper.threadedregions.RegionizedServer");

    private Scheduling() {
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    /** Runs on the thread owning {@code location}'s region, next tick. */
    public static void region(Plugin plugin, Location location, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, location, task);
    }

    /** Runs on the thread owning {@code entity}; silently drops the task if the entity is removed. */
    public static void entity(Plugin plugin, Entity entity, Runnable task) {
        entity.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    /**
     * The same, a number of ticks later.
     * <p>
     * Needed where the point is to read state back <em>after</em> the server has finished changing it —
     * an inventory edit is applied by Minecraft after the click event returns, so anything that reads
     * the result has to wait a tick. Relying on {@link #entity} being "next tick anyway" would be
     * relying on a detail of the scheduler rather than saying what is meant.
     */
    public static void entityLater(Plugin plugin, Entity entity, long delayTicks, Runnable task) {
        entity.getScheduler().runDelayed(plugin, ignored -> task.run(), null, Math.max(1L, delayTicks));
    }

    /** Repeating task pinned to the region owning {@code location}. */
    public static ScheduledTask regionTimer(Plugin plugin, Location location, long delayTicks,
                                            long periodTicks, Consumer<ScheduledTask> task) {
        return Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, task,
                Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    /** Repeating task pinned to {@code entity}; automatically cancelled when the entity is removed. */
    public static ScheduledTask entityTimer(Plugin plugin, Entity entity, long delayTicks,
                                            long periodTicks, Consumer<ScheduledTask> task) {
        return entityTimer(plugin, entity, delayTicks, periodTicks, task, null);
    }

    /**
     * Repeating task pinned to {@code entity}, with a callback for when the entity goes away.
     * <p>
     * The retired callback matters more than it looks: a player's entity is <em>recreated</em> on
     * respawn, so any long-lived task bound to the old one silently stops. Without being told, a caller
     * that remembers "this player already has a task" would never schedule a replacement.
     */
    public static ScheduledTask entityTimer(Plugin plugin, Entity entity, long delayTicks,
                                            long periodTicks, Consumer<ScheduledTask> task,
                                            Runnable retired) {
        return entity.getScheduler().runAtFixedRate(plugin, task, retired,
                Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    /** Global-region task, for world/server wide state that is not tied to a single location. */
    public static void global(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    public static void globalLater(Plugin plugin, long delayTicks, Runnable task) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), Math.max(1L, delayTicks));
    }

    public static ScheduledTask globalTimer(Plugin plugin, long delayTicks, long periodTicks,
                                            Consumer<ScheduledTask> task) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task,
                Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    /** Off-thread work: disk I/O, HTTP, anything that must not touch the Bukkit API. */
    public static void async(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }

    public static ScheduledTask asyncTimer(Plugin plugin, long delaySeconds, long periodSeconds,
                                           Consumer<ScheduledTask> task) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task,
                Math.max(1L, delaySeconds), Math.max(1L, periodSeconds), TimeUnit.SECONDS);
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
