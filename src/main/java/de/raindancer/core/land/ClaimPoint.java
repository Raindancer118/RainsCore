package de.raindancer.core.land;

/** A single block column (x/z) used as a claim polygon vertex. */
public record ClaimPoint(int x, int z) {

    public String serialize() {
        return x + "," + z;
    }

    public static ClaimPoint deserialize(String raw) {
        String[] parts = raw.split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed claim point: " + raw);
        }
        return new ClaimPoint(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
    }

    public double distanceSquared(int otherX, int otherZ) {
        double dx = x - otherX;
        double dz = z - otherZ;
        return dx * dx + dz * dz;
    }
}
