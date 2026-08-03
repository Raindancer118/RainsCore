package de.raindancer.core.land;

import java.util.Locale;
import java.util.Optional;

/** Who gets to read what happened in somebody's claim. */
public enum BroadcastScope {

    /** Everybody online. Loud, and the funniest — the point of announcing a kick at all. */
    EVERYONE("Everyone", "<gold>The whole server reads it"),
    /** The people the claim belongs to, plus whoever it happened to. */
    CLAIM("The claim", "<green>Owners, members and the person it happened to"),
    /** Anybody close enough to have plausibly watched it happen. */
    NEARBY("Nearby", "<aqua>Anybody within the configured radius");

    private final String displayName;
    private final String description;

    BroadcastScope(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public BroadcastScope next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** So a right-click can walk the cycle backwards, the way it does everywhere else in the GUI. */
    public BroadcastScope previous() {
        return values()[(ordinal() - 1 + values().length) % values().length];
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<BroadcastScope> byKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalised) {
            case "EVERYONE", "ALL", "SERVER", "GLOBAL" -> Optional.of(EVERYONE);
            case "CLAIM", "MEMBERS", "OWNERS" -> Optional.of(CLAIM);
            case "NEARBY", "NEAR", "RADIUS", "LOCAL" -> Optional.of(NEARBY);
            default -> Optional.empty();
        };
    }
}
