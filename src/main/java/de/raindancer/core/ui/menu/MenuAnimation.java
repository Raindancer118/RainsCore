package de.raindancer.core.ui.menu;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Frames drawn into an open menu over time — a coin spinning, reels turning, a crate opening.
 *
 * <p>Runs on the viewer's own thread. Frames can slow down towards the end, which is what makes a spin
 * read as a spin. If the window is closed part-way, the remaining frames are skipped but the end still
 * runs: whatever the animation was revealing has already happened and must still be told.
 */
public final class MenuAnimation {

    private MenuAnimation() {
    }

    /**
     * When each frame is due, in ticks from the start: gaps growing evenly from {@code firstGap} to
     * {@code lastGap}.
     */
    public static List<Integer> schedule(int frames, int firstGap, int lastGap) {
        int count = Math.max(1, frames);
        int first = Math.max(1, firstGap);
        int last = Math.max(1, lastGap);
        List<Integer> at = new ArrayList<>(count);
        int tick = 0;
        for (int frame = 0; frame < count; frame++) {
            double share = count == 1 ? 0 : (double) frame / (count - 1);
            tick += (int) Math.round(first + (last - first) * share);
            at.add(tick);
        }
        return at;
    }

    /**
     * Plays an animation in a menu that is already open.
     *
     * @param frame given the frame's index; draws it into the menu (call {@code refresh()} or set slots)
     * @param done  run once after the last frame, or as soon as the window is closed
     */
    public static void play(Plugin plugin, Menu menu, List<Integer> schedule, IntConsumer frame, Runnable done) {
        Player viewer = menu.viewer();
        Run run = new Run(schedule, frame, done);
        Scheduling.entityTimer(plugin, viewer, 1L, 1L, task -> {
            run.tick(isOpen(viewer, menu));
            if (run.finished()) {
                task.cancel();
            }
        }, run::end);
    }

    /**
     * Redraws a menu every {@code periodTicks} for as long as it stays open — a shared race or round that
     * moves on whether this viewer does anything or not. {@code closed} runs once, when they leave it.
     */
    public static void loop(Plugin plugin, Menu menu, long periodTicks, Runnable frame, Runnable closed) {
        Player viewer = menu.viewer();
        Loop loop = new Loop(frame, closed);
        Scheduling.entityTimer(plugin, viewer, periodTicks, periodTicks, task -> {
            if (!loop.tick(isOpen(viewer, menu))) {
                task.cancel();
            }
        }, loop::end);
    }

    /** The looping, apart from any server. */
    static final class Loop {
        private final Runnable frame;
        private final Runnable closed;
        private boolean ended;

        Loop(Runnable frame, Runnable closed) {
            this.frame = frame;
            this.closed = closed == null ? () -> { } : closed;
        }

        /** @return whether to keep going */
        boolean tick(boolean stillOpen) {
            if (ended) {
                return false;
            }
            if (!stillOpen) {
                end();
                return false;
            }
            frame.run();
            return true;
        }

        void end() {
            if (!ended) {
                ended = true;
                closed.run();
            }
        }
    }

    private static boolean isOpen(Player viewer, Menu menu) {
        InventoryView open = viewer.getOpenInventory();
        return open != null && open.getTopInventory() != null && open.getTopInventory().getHolder(false) == menu;
    }

    /** The stepping, apart from any server — what the tests drive. */
    static final class Run {
        private final List<Integer> schedule;
        private final IntConsumer frame;
        private final Runnable done;
        private int tick;
        private int next;
        private boolean ended;

        Run(List<Integer> schedule, IntConsumer frame, Runnable done) {
            this.schedule = List.copyOf(schedule);
            this.frame = frame;
            this.done = done;
        }

        void tick(boolean stillOpen) {
            if (ended) {
                return;
            }
            if (!stillOpen) {
                end();
                return;
            }
            tick++;
            while (next < schedule.size() && schedule.get(next) <= tick) {
                frame.accept(next);
                next++;
            }
            if (next >= schedule.size()) {
                end();
            }
        }

        void end() {
            if (!ended) {
                ended = true;
                done.run();
            }
        }

        boolean finished() {
            return ended;
        }
    }
}
