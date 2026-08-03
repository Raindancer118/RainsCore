package de.raindancer.core.land;

import java.util.UUID;

/**
 * A ban or a timeout on a claim. A timeout is simply a ban with an expiry timestamp, which keeps the
 * enforcement path in {@code ProtectionService} down to a single check.
 */
public final class ClaimBan {

    private final UUID uuid;
    private final UUID issuedBy;
    private final long issuedAt;
    /** Epoch millis at which the ban lapses, or {@code 0} for a permanent ban. */
    private final long expiresAt;
    private final String reason;

    public ClaimBan(UUID uuid, UUID issuedBy, long issuedAt, long expiresAt, String reason) {
        this.uuid = uuid;
        this.issuedBy = issuedBy;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.reason = reason == null ? "" : reason;
    }

    public static ClaimBan permanent(UUID uuid, UUID issuedBy, String reason) {
        return new ClaimBan(uuid, issuedBy, System.currentTimeMillis(), 0L, reason);
    }

    public static ClaimBan timeout(UUID uuid, UUID issuedBy, long durationMillis, String reason) {
        return new ClaimBan(uuid, issuedBy, System.currentTimeMillis(),
                System.currentTimeMillis() + Math.max(1L, durationMillis), reason);
    }

    public UUID uuid() {
        return uuid;
    }

    public UUID issuedBy() {
        return issuedBy;
    }

    public long issuedAt() {
        return issuedAt;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public String reason() {
        return reason;
    }

    public boolean permanent() {
        return expiresAt <= 0L;
    }

    public boolean expired() {
        return !permanent() && System.currentTimeMillis() >= expiresAt;
    }

    public long remainingMillis() {
        return permanent() ? Long.MAX_VALUE : Math.max(0L, expiresAt - System.currentTimeMillis());
    }
}
