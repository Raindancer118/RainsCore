package de.raindancer.core.land;


import java.util.UUID;

/**
 * An area in which players may not create claims. Shares {@link ClaimShape} with claims, so admins can
 * carve out polygonal zones with the same selection stick.
 */
public final class NoClaimZone {

    private final String name;
    private final UUID worldId;
    private String worldName;
    private ClaimShape shape;
    private final long createdAt;

    public NoClaimZone(String name, UUID worldId, String worldName, ClaimShape shape, long createdAt) {
        this.name = name;
        this.worldId = worldId;
        this.worldName = worldName;
        this.shape = shape;
        this.createdAt = createdAt;
    }

    public String name() {
        return name;
    }

    public UUID worldId() {
        return worldId;
    }

    public String worldName() {
        return worldName;
    }

    public void worldName(String worldName) {
        this.worldName = worldName;
    }

    public ClaimShape shape() {
        return shape;
    }

    public void shape(ClaimShape shape) {
        this.shape = shape;
    }

    public long createdAt() {
        return createdAt;
    }
}
