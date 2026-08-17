package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;

import java.util.ArrayList;
import java.util.List;

/**
 * What was at every position a {@link BatchBuilder} overwrote, in the order it overwrote them.
 *
 * <p>This is the piece that turns "tear a structure down" into a real inverse of "build it" rather
 * than a bulldozer: a caller that built a wall over a hillside and then decides against it gets the
 * hillside back, not a flat scar of air. Handing a snapshot's own placements to a fresh
 * {@link BatchBuilder} is what a teardown <em>is</em> — restoring is placing, with the recorded
 * original material as the target.
 */
public final class BuildSnapshot {

    private final List<Placement> placements;

    public BuildSnapshot(List<Placement> placements) {
        this.placements = List.copyOf(placements);
    }

    public static BuildSnapshot empty() {
        return new BuildSnapshot(List.of());
    }

    public List<Placement> placements() {
        return placements;
    }

    public boolean isEmpty() {
        return placements.isEmpty();
    }

    public int size() {
        return placements.size();
    }

    /** The placements a teardown needs: put back exactly what was recorded here. */
    public List<Placement> asRestorePlacements() {
        return new ArrayList<>(placements);
    }

    /** What one position held before it was overwritten. */
    public record Placement(Spot spot, String material) {
    }
}
