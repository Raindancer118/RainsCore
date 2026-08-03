package de.raindancer.core.safety;

/**
 * A block position — where a player's feet would be.
 *
 * <p>Block coordinates rather than a {@code Location} on purpose: everything in here is arithmetic
 * on integers, and a value type with no server in it is what lets the rules be tested. The world is
 * a name for the same reason.
 */
public record Spot(String world, int x, int y, int z) {

    public Spot offset(int dx, int dy, int dz) {
        return new Spot(world, x + dx, y + dy, z + dz);
    }

    public Spot atHeight(int newY) {
        return new Spot(world, x, newY, z);
    }

    /** Squared distance, so nothing has to take a square root to compare two of these. */
    public long distanceSquaredTo(Spot other) {
        long dx = (long) x - other.x;
        long dy = (long) y - other.y;
        long dz = (long) z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** The middle of the block, which is where a player should actually be put. */
    public double centreX() {
        return x + 0.5;
    }

    public double centreZ() {
        return z + 0.5;
    }

    @Override
    public String toString() {
        return world + " " + x + ", " + y + ", " + z;
    }
}
