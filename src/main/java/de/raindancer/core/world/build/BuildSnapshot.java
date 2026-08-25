package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;

import java.util.ArrayList;
import java.util.List;

/**
 * What was there before a build touched it — the one thing that makes a build reversible.
 *
 * <p>Holds a record only for positions a build <em>actually changed</em>. Everything else would be
 * an invented block: undoing a placement the world refused, or one that was already the right
 * material, writes a block nobody ever put there.
 */
public record BuildSnapshot(List<Placement> placements) {

    /** One position, and the material that was there before. */
    public record Placement(Spot spot, String material) {
    }

    private static final BuildSnapshot EMPTY = new BuildSnapshot(List.of());

    public BuildSnapshot {
        placements = List.copyOf(placements);
    }

    public static BuildSnapshot empty() {
        return EMPTY;
    }

    public int size() {
        return placements.size();
    }

    public boolean isEmpty() {
        return placements.isEmpty();
    }

    /**
     * The queue that puts everything back, <strong>in reverse</strong>.
     *
     * <p>Reverse because a build can cover the same position twice — a road crossing its own
     * hairpin, a wall rebuilt over a sealed gate. Replaying the record forwards leaves the
     * <em>second</em> original material standing; replaying it backwards ends on the first, which is
     * what was actually there before anything happened.
     */
    public List<BatchBuilder.Placement> asRestorePlacements() {
        List<BatchBuilder.Placement> restore = new ArrayList<>(placements.size());
        for (int i = placements.size() - 1; i >= 0; i--) {
            Placement placement = placements.get(i);
            restore.add(new BatchBuilder.Placement(placement.spot(), placement.material()));
        }
        return List.copyOf(restore);
    }
}
