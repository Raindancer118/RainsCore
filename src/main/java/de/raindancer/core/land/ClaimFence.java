package de.raindancer.core.land;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The physical fence around a claim.
 * <p>
 * Two pieces of state matter, and they are kept apart on purpose:
 * <ul>
 *   <li>{@link #segments()} — what is actually standing in the world right now, so the plugin can take
 *       exactly its own blocks back down again and never touch a player's build.</li>
 *   <li>{@link #suppressed()} — columns the owner deliberately cleared. A resize or a rebuild must not
 *       quietly close a gap somebody made on purpose, so these stay empty until the owner says otherwise.</li>
 * </ul>
 * Gates live in {@code segments} with {@link FenceSegment#gate()} set, which is how a swapped-in gate
 * survives the claim growing or shrinking.
 */
public final class ClaimFence {

    /** Whether the owner wants a fence at all. Off by default — a fence is always opt-in. */
    private boolean enabled;
    private Material material = Material.OAK_FENCE;
    private final Map<ClaimPoint, FenceSegment> segments = new HashMap<>();
    private final Set<ClaimPoint> suppressed = new HashSet<>();

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Material material() {
        return material;
    }

    public void material(Material material) {
        if (isFence(material)) {
            this.material = material;
        }
    }

    public Map<ClaimPoint, FenceSegment> segments() {
        return Collections.unmodifiableMap(segments);
    }

    public Set<ClaimPoint> suppressed() {
        return Collections.unmodifiableSet(suppressed);
    }

    public int standingCount() {
        return segments.size();
    }

    /** Total blocks standing, counting stacked segments. */
    public int standingBlocks() {
        int total = 0;
        for (FenceSegment segment : segments.values()) {
            total += segment.height();
        }
        return total;
    }

    public int gateCount() {
        int total = 0;
        for (FenceSegment segment : segments.values()) {
            if (segment.gate()) {
                total++;
            }
        }
        return total;
    }

    public Optional<FenceSegment> segmentAt(ClaimPoint point) {
        return Optional.ofNullable(segments.get(point));
    }

    public void put(ClaimPoint point, FenceSegment segment) {
        segments.put(point, segment);
        suppressed.remove(point);
    }

    public FenceSegment remove(ClaimPoint point) {
        return segments.remove(point);
    }

    public boolean isSuppressed(ClaimPoint point) {
        return suppressed.contains(point);
    }

    /** Marks a column as deliberately open; a later sync leaves it alone. */
    public void suppress(ClaimPoint point) {
        suppressed.add(point);
        segments.remove(point);
    }

    public void unsuppress(ClaimPoint point) {
        suppressed.remove(point);
    }

    /** Forgets every gap the owner made, so the next build closes the ring again. */
    public int clearSuppressions() {
        int cleared = suppressed.size();
        suppressed.clear();
        return cleared;
    }

    public void clearSegments() {
        segments.clear();
    }

    /**
     * Drops bookkeeping for columns that are no longer on the outline.
     * <p>
     * Without this the suppression set would grow forever as a claim is reshaped, and a column that once
     * had a gap would stay open if the claim ever grew back over it.
     */
    public void pruneTo(Set<ClaimPoint> outline) {
        suppressed.retainAll(outline);
    }

    public Set<ClaimPoint> segmentPoints() {
        return new LinkedHashSet<>(segments.keySet());
    }

    public void restore(Map<ClaimPoint, FenceSegment> loadedSegments, Set<ClaimPoint> loadedSuppressed) {
        segments.clear();
        segments.putAll(loadedSegments);
        suppressed.clear();
        suppressed.addAll(loadedSuppressed);
    }

    /**
     * Whether the material may be used for the claim border.
     * <p>
     * Covers fences and walls — a stone wall reads very differently from a wooden fence and both are
     * legitimate ways to mark a claim, so owners can pick either.
     */
    public static boolean isFence(Material material) {
        return material != null && (Tag.FENCES.isTagged(material) || Tag.WALLS.isTagged(material));
    }

    public static boolean isWall(Material material) {
        return material != null && Tag.WALLS.isTagged(material);
    }

    public static boolean isGate(Material material) {
        return material != null && Tag.FENCE_GATES.isTagged(material);
    }

    /**
     * The gate that belongs to a fence, e.g. {@code OAK_FENCE} → {@code OAK_FENCE_GATE}.
     * <p>
     * Walls and nether brick fence have no matching gate, so the result is empty for them; the GUI says
     * so rather than guessing a mismatched material.
     */
    public static Optional<Material> gateFor(Material fence) {
        if (!isFence(fence) || isWall(fence)) {
            return Optional.empty();
        }
        Material gate = Material.matchMaterial(fence.name() + "_GATE");
        return gate != null && isGate(gate) ? Optional.of(gate) : Optional.empty();
    }

    /** The fence that belongs to a gate, used when detecting an owner's manual swap. */
    public static Optional<Material> fenceForGate(Material gate) {
        if (!isGate(gate)) {
            return Optional.empty();
        }
        String name = gate.name();
        if (!name.endsWith("_GATE")) {
            return Optional.empty();
        }
        Material fence = Material.matchMaterial(name.substring(0, name.length() - "_GATE".length()));
        return fence != null && isFence(fence) ? Optional.of(fence) : Optional.empty();
    }
}
