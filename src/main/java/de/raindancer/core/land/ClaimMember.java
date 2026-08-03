package de.raindancer.core.land;

import java.util.EnumSet;
import java.util.UUID;

/**
 * A player who has been explicitly trusted on a claim.
 * <p>
 * Three independent sets, because the plan asks for exactly that separation:
 * <ul>
 *   <li>{@link #permissions()} — what this player may do inside the claim.</li>
 *   <li>{@link #adminPermissions()} — what this player may change about the claim.</li>
 *   <li>{@link #grantablePermissions()} — which permissions this player may hand to others
 *       (e.g. "may grant the right to open doors").</li>
 * </ul>
 */
public final class ClaimMember {

    private final UUID uuid;
    private final EnumSet<ClaimPermission> permissions;
    private final EnumSet<ClaimAdminPermission> adminPermissions;
    private final EnumSet<ClaimPermission> grantablePermissions;
    private long addedAt;

    public ClaimMember(UUID uuid) {
        this(uuid, EnumSet.noneOf(ClaimPermission.class), EnumSet.noneOf(ClaimAdminPermission.class),
                EnumSet.noneOf(ClaimPermission.class), System.currentTimeMillis());
    }

    public ClaimMember(UUID uuid, EnumSet<ClaimPermission> permissions,
                       EnumSet<ClaimAdminPermission> adminPermissions,
                       EnumSet<ClaimPermission> grantablePermissions, long addedAt) {
        this.uuid = uuid;
        this.permissions = permissions;
        this.adminPermissions = adminPermissions;
        this.grantablePermissions = grantablePermissions;
        this.addedAt = addedAt;
    }

    public UUID uuid() {
        return uuid;
    }

    public EnumSet<ClaimPermission> permissions() {
        return permissions;
    }

    public EnumSet<ClaimAdminPermission> adminPermissions() {
        return adminPermissions;
    }

    public EnumSet<ClaimPermission> grantablePermissions() {
        return grantablePermissions;
    }

    public long addedAt() {
        return addedAt;
    }

    public void addedAt(long addedAt) {
        this.addedAt = addedAt;
    }

    public boolean has(ClaimPermission permission) {
        return permissions.contains(permission);
    }

    public boolean has(ClaimAdminPermission permission) {
        return adminPermissions.contains(permission);
    }

    public boolean isClaimAdmin() {
        return !adminPermissions.isEmpty();
    }

    /** Grants the default trust package used by {@code /claim trust} without explicit permissions. */
    public void applyDefaultTrust() {
        permissions.addAll(EnumSet.of(
                ClaimPermission.ENTER,
                ClaimPermission.BUILD,
                ClaimPermission.BREAK,
                ClaimPermission.CONTAINERS,
                ClaimPermission.DOORS,
                ClaimPermission.REDSTONE,
                ClaimPermission.BEDS,
                ClaimPermission.WORKSTATIONS,
                ClaimPermission.ANIMALS,
                ClaimPermission.VEHICLES,
                ClaimPermission.ITEM_FRAMES,
                ClaimPermission.BUCKETS,
                ClaimPermission.ITEM_PICKUP,
                ClaimPermission.TRADE));
    }
}
