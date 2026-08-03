package de.raindancer.core.world.protection;

import java.util.Locale;
import java.util.Optional;

/** How a server admin exposes a {@link LandFlag} to claim owners. */
public enum FlagPolicy {

    /** Owners may toggle the flag freely. */
    AVAILABLE("Available", "<green>Owners may toggle this flag"),
    /** Flag is always on and cannot be changed. */
    FORCED_ON("Forced on", "<gold>Always allowed, owners cannot change it"),
    /** Flag is always off and cannot be changed. */
    FORCED_OFF("Forced off", "<gold>Always denied, owners cannot change it"),
    /** Flag is not offered at all and its protection is never applied. */
    DISABLED("Disabled", "<red>Removed from the plugin entirely");

    private final String displayName;
    private final String description;

    FlagPolicy(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public FlagPolicy next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<FlagPolicy> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (FlagPolicy policy : values()) {
            if (policy.name().equals(normalised)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }
}
