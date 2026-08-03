package de.raindancer.core.land;

import org.bukkit.Material;

/**
 * One column of the physical claim fence that is actually standing in the world.
 *
 * @param baseY    lowest block of the segment
 * @param height   how many blocks are stacked upwards from {@code baseY}
 * @param material the block placed — a fence, or a fence gate the owner swapped in
 * @param gate     {@code true} when the owner replaced this column with a gate
 */
public record FenceSegment(int baseY, int height, Material material, boolean gate) {

    public FenceSegment {
        height = Math.max(1, height);
    }

    public int topY() {
        return baseY + height - 1;
    }

    public boolean coversY(int y) {
        return y >= baseY && y <= topY();
    }

    public String serialize() {
        return baseY + ";" + height + ";" + material.name() + ";" + gate;
    }

    /** Returns {@code null} for malformed or unknown-material entries rather than throwing. */
    public static FenceSegment deserialize(String raw) {
        String[] parts = raw.split(";");
        if (parts.length < 4) {
            return null;
        }
        Material material = Material.matchMaterial(parts[2]);
        if (material == null) {
            return null;
        }
        try {
            return new FenceSegment(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    material, Boolean.parseBoolean(parts[3]));
        } catch (NumberFormatException malformed) {
            return null;
        }
    }
}
