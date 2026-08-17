package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;

import java.util.ArrayList;
import java.util.List;

/**
 * Places a queue of blocks a few at a time, over as many calls as it takes — the "huge" in a huge
 * town wall stops being "every block in one tick" once something owns pacing this the same way
 * {@code claims-module}'s own fence builder already paces itself, only written once, here.
 *
 * <h2>Why this does not schedule itself</h2>
 * A ticking task is Bukkit — this is not, deliberately, the same reason {@link Ground} takes a
 * {@link Spot} rather than a {@code Block}. A caller drives it with {@link #advance(int)} from
 * whatever repeating task it likes ({@code Scheduling.regionTimer} in production, a plain loop in a
 * test), so the pacing logic itself — how many blocks make one batch, when it is done — is testable
 * with a fake {@link Ground} and no server at all.
 *
 * <h2>Snapshots, and how a teardown is built from this same class</h2>
 * Every position this overwrites has its previous material recorded before the new one is written,
 * because the write is one-way otherwise. {@link #snapshotSoFar()} is what a caller persists
 * alongside whatever it built; tearing that structure back down later is simply constructing a new
 * {@code BatchBuilder} whose target placements are that snapshot's own recorded materials
 * ({@link BuildSnapshot#asRestorePlacements()}) and running it the same way.
 */
public final class BatchBuilder {

    /** One block this builder still has to place. */
    public record Placement(Spot spot, String material) {
    }

    private final Ground ground;
    private final List<Placement> queue;
    private int cursor;
    private final List<BuildSnapshot.Placement> captured = new ArrayList<>();

    public BatchBuilder(Ground ground, List<Placement> placements) {
        this.ground = ground;
        this.queue = List.copyOf(placements);
        this.cursor = 0;
    }

    public boolean isDone() {
        return cursor >= queue.size();
    }

    public int remaining() {
        return queue.size() - cursor;
    }

    public int total() {
        return queue.size();
    }

    public int placedSoFar() {
        return cursor;
    }

    /**
     * Places up to {@code maxBlocks} more, skipping any spot the ground refuses (unloaded, outside
     * the world, an unknown material) rather than stopping the whole batch on one bad entry.
     *
     * @return how many were actually placed this call
     */
    public int advance(int maxBlocks) {
        int placed = 0;
        while (placed < maxBlocks && !isDone()) {
            Placement next = queue.get(cursor);
            cursor++;
            if (!ground.isLoaded(next.spot())) {
                continue;
            }
            String before = ground.materialAt(next.spot());
            if (before == null) {
                continue;
            }
            if (ground.set(next.spot(), next.material())) {
                captured.add(new BuildSnapshot.Placement(next.spot(), before));
                placed++;
            }
        }
        return placed;
    }

    /** Everything overwritten so far, in the order it happened — the material to restore, if undone. */
    public BuildSnapshot snapshotSoFar() {
        return new BuildSnapshot(captured);
    }
}
