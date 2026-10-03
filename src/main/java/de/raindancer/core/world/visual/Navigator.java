package de.raindancer.core.world.visual;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.messages.Messages;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Walks somebody to a point: a {@link PathTrail} shown to them alone, the distance on their action
 * bar, and a line in chat when they get there — then it stops by itself. One navigation per player;
 * a new one replaces the old. Gives up after {@link #MAX_DURATION} so a forgotten one never runs on.
 *
 * <p>{@link #progress} is the decision, Bukkit-free; the rest is the per-player timer (on the
 * player's own scheduler, so Folia-safe) and the drawing. Wording is Core's {@code navigation.*},
 * which a plugin can say its own way through {@code Messages.overrideFor}.
 */
public final class Navigator {

    /** Close enough to call it there. */
    public static final double ARRIVED_WITHIN = 3.0;
    static final long PERIOD_TICKS = 10L;
    static final Duration MAX_DURATION = Duration.ofMinutes(15);
    private static final String BAR_OWNER = "core-navigation";

    /** Where somebody is, relative to where they are going. */
    public record Progress(Kind kind, double distance) {
        public enum Kind { ON_THE_WAY, ARRIVED, OTHER_WORLD }
    }

    private record Trip(ScheduledTask task, String owner) {
    }

    private final Plugin plugin;
    private final ActionBars actionBars;
    private final Messages messages;
    private final Map<UUID, Trip> live = new ConcurrentHashMap<>();

    public Navigator(Plugin plugin, ActionBars actionBars, Messages messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.actionBars = actionBars;
        this.messages = messages;
    }

    public static Progress progress(String fromWorld, double fx, double fy, double fz,
                                    String toWorld, double tx, double ty, double tz) {
        if (!Objects.equals(fromWorld, toWorld)) {
            return new Progress(Progress.Kind.OTHER_WORLD, 0);
        }
        double dx = tx - fx;
        double dy = ty - fy;
        double dz = tz - fz;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new Progress(distance <= ARRIVED_WITHIN ? Progress.Kind.ARRIVED : Progress.Kind.ON_THE_WAY,
                distance);
    }

    /**
     * Starts walking {@code player} to {@code target}, replacing any navigation they had.
     *
     * @param owner      the plugin this is on behalf of, for its own wording ({@code Messages.sendFor})
     * @param label      what the target is called on the action bar — "Anna's spot"
     * @param showTrail  whether this player sees the particle trail right now — asked every tick, so a
     *                   player switching it off mid-trip is obeyed at once
     */
    public void navigate(Player player, Location target, String owner, String label,
                         Predicate<Player> showTrail) {
        Objects.requireNonNull(target, "target");
        stop(player);
        UUID id = player.getUniqueId();
        long until = System.currentTimeMillis() + MAX_DURATION.toMillis();
        Location goal = target.clone();
        String name = label == null ? "" : label;
        AtomicReference<Trip> self = new AtomicReference<>();
        ScheduledTask task = Scheduling.entityTimer(plugin, player, 1L, PERIOD_TICKS, scheduled -> {
            if (!player.isOnline() || System.currentTimeMillis() > until) {
                end(id, scheduled);
                return;
            }
            tick(player, goal, owner, name, showTrail);
        // Retired with the entity — a logout, or a respawn — after which the task never runs again to
        // end itself, and isNavigating would answer true for a trip nothing is drawing.
        }, () -> {
            if (live.remove(id, self.get()) && actionBars != null) {
                actionBars.clear(id, BAR_OWNER);
            }
        });
        if (task != null) {
            Trip trip = new Trip(task, owner);
            self.set(trip);
            live.put(id, trip);
        }
        if (messages != null) {
            messages.sendFor(owner, player, "navigation.started", "target", name);
        }
    }

    private void tick(Player player, Location goal, String owner, String label, Predicate<Player> showTrail) {
        Location at = player.getLocation();
        Progress progress = progress(at.getWorld() == null ? null : at.getWorld().getName(),
                at.getX(), at.getY(), at.getZ(),
                goal.getWorld() == null ? null : goal.getWorld().getName(), goal.getX(), goal.getY(), goal.getZ());
        switch (progress.kind()) {
            case ARRIVED -> {
                if (messages != null) {
                    messages.sendFor(owner, player, "navigation.arrived", "target", label);
                }
                stop(player);
            }
            case OTHER_WORLD -> bar(player, owner, "navigation.other-world", label, 0);
            case ON_THE_WAY -> {
                bar(player, owner, "navigation.distance", label, progress.distance());
                if (showTrail == null || showTrail.test(player)) {
                    PathTrail.draw(player, player.getWorld(), PathTrail.toward(at.getX(), at.getY() + 1, at.getZ(),
                                    goal.getX(), goal.getY() + 1, goal.getZ(), 1.5, 1.0, 12),
                            new Particle.DustOptions(Color.YELLOW, 1.0f));
                }
            }
        }
    }

    private void bar(Player player, String owner, String key, String label, double distance) {
        if (actionBars == null || messages == null) {
            return;
        }
        actionBars.show(player.getUniqueId(), BAR_OWNER,
                messages.get(messages.keyFor(owner, key), "target", label,
                        "blocks", String.valueOf(Math.round(distance))),
                Duration.ofMillis(PERIOD_TICKS * 50 + 1000), ActionBarPriority.NORMAL);
    }

    /** Stops {@code player}'s navigation, if they had one. @return whether they had */
    public boolean stop(Player player) {
        if (player == null) {
            return false;
        }
        Trip trip = live.remove(player.getUniqueId());
        if (trip == null) {
            return false;
        }
        trip.task().cancel();
        if (actionBars != null) {
            actionBars.clear(player.getUniqueId(), BAR_OWNER);
        }
        return true;
    }

    private void end(UUID id, ScheduledTask scheduled) {
        scheduled.cancel();
        live.remove(id);
        if (actionBars != null) {
            actionBars.clear(id, BAR_OWNER);
        }
    }

    public boolean isNavigating(UUID player) {
        return live.containsKey(player);
    }

    /** Everything, for a plugin unloading. */
    public void stopAll() {
        live.forEach((id, trip) -> {
            trip.task().cancel();
            if (actionBars != null) {
                actionBars.clear(id, BAR_OWNER);
            }
        });
        live.clear();
    }
}
