package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;

import java.util.ArrayList;
import java.util.List;

/**
 * A queue of blocks to place, a few at a time, recording what it covered as it goes.
 *
 * <h2>Why a queue rather than a loop</h2>
 * A town wall is tens of thousands of blocks. Placing them in one pass holds the server for as long
 * as it takes and shows up to everybody online as a freeze; placing them a batch per tick is the
 * difference between a wall going up and a server going down. The pacing itself is the caller's —
 * this only knows how to do <em>some</em> of the work and say whether there is more.
 *
 * <p>Nothing here touches Bukkit: {@link Ground} is the seam, so a build can be run against a map in
 * a test and asserted block by block.
 */
public final class BatchBuilder {

    /** One position, and the material to put there. */
    public record Placement(Spot spot, String material) {
    }

    private final Ground ground;
    private final List<Placement> queue;
    private final List<BuildSnapshot.Placement> covered = new ArrayList<>();
    private int cursor;

    public BatchBuilder(Ground ground, List<Placement> queue) {
        this.ground = ground;
        this.queue = List.copyOf(queue);
    }

    public int total() {
        return queue.size();
    }

    /** How many of the queue have been dealt with — placed, skipped or refused alike. */
    public int placed() {
        return cursor;
    }

    public int remaining() {
        return queue.size() - cursor;
    }

    public boolean isDone() {
        return cursor >= queue.size();
    }

    /**
     * Deals with up to {@code count} more of the queue.
     *
     * @return how many entries were consumed, which is {@code 0} once the queue is done
     */
    public int advance(int count) {
        int taken = 0;
        while (taken < count && !isDone()) {
            Placement placement = queue.get(cursor);
            cursor++;
            taken++;
            place(placement);
        }
        return taken;
    }

    /**
     * What has been covered so far — usable mid-build, which is what lets a build interrupted by a
     * restart or a cancelled task still be undone exactly as far as it got.
     */
    public BuildSnapshot snapshotSoFar() {
        return new BuildSnapshot(List.copyOf(covered));
    }

    private void place(Placement placement) {
        if (!ground.isLoaded(placement.spot())) {
            return;
        }
        String before = ground.materialAt(placement.spot());
        if (before == null || before.equals(placement.material())) {
            return;
        }
        if (ground.set(placement.spot(), placement.material())) {
            covered.add(new BuildSnapshot.Placement(placement.spot(), before));
        }
    }
}
