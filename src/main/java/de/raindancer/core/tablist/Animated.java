package de.raindancer.core.tablist;

import java.util.List;

/**
 * A header or footer that changes as it is redrawn.
 *
 * <h2>Why it is worth having</h2>
 * Because a header that never changes is a header nobody reads twice, and the tablist is one of the
 * few places on a server where there is room to say something — the rules, a vote that is running,
 * the next event. Cycling two or three lines through it is the difference between somebody seeing
 * that and not.
 *
 * <p>The other half is the frame rate. The tablist redraws twice a second, so a frame per redraw is
 * not an animation, it is a strobe: {@link #everyTicks} is how a frame is made to last.
 *
 * <p>A plain string is a one-frame animation, so nothing anywhere has to hold two kinds of header.
 */
public final class Animated {

    private final List<String> frames;
    private final int everyTicks;

    private Animated(List<String> frames, int everyTicks) {
        this.frames = frames;
        this.everyTicks = Math.max(1, everyTicks);
    }

    /** A header that does not move. */
    public static Animated of(String single) {
        return new Animated(single == null || single.isEmpty() ? List.of() : List.of(single), 1);
    }

    /** One that cycles through these, in order, wrapping round. */
    public static Animated of(List<String> frames) {
        List<String> kept = frames == null ? List.of()
                : frames.stream().filter(frame -> frame != null).toList();
        return new Animated(kept, 1);
    }

    /**
     * How many refreshes each frame lasts.
     *
     * <p>The tablist refreshes about twice a second, so 4 is roughly two seconds a frame.
     */
    public Animated everyTicks(int ticks) {
        return new Animated(frames, ticks);
    }

    /** Whether there is anything to redraw for. */
    public boolean isAnimated() {
        return frames.size() > 1;
    }

    public List<String> frames() {
        return frames;
    }

    /**
     * The frame to show on this refresh.
     *
     * <p>{@code Math.floorMod} rather than {@code %}: a negative tick with the ordinary remainder
     * gives a negative index and an exception, and a counter that has wrapped is not a reason for a
     * tablist to stop drawing.
     */
    public String frameAt(long tick) {
        if (frames.isEmpty()) {
            return "";
        }
        if (frames.size() == 1) {
            return frames.getFirst();
        }
        long slowed = Math.floorDiv(tick, everyTicks);
        return frames.get((int) Math.floorMod(slowed, frames.size()));
    }
}
