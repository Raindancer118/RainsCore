package de.raindancer.core.land;

import java.util.Locale;
import java.util.Optional;

/**
 * How a server admin exposes one of the optional claim perks — effects, auto-equip, the pantry.
 * <p>
 * The counterpart to {@link FlagPolicy}, but for whole features rather than single protection switches.
 * A perk has a per-claim on/off switch of its own, so an admin has three genuinely different things to
 * say about it: leave it to the owner, run it in every claim, or take it away entirely.
 */
public enum FeaturePolicy {

    /** Owners decide for their own claim. */
    AVAILABLE("Available", "<green>Owners decide for their own claim"),
    /** On in every claim; the owner's switch is locked. */
    FORCED_ON("Forced on", "<gold>On in every claim, owners cannot switch it off"),
    /** Not offered at all: hidden from the GUI and never applied. */
    FORCED_OFF("Forced off", "<red>Removed from the plugin entirely");

    private final String displayName;
    private final String description;

    FeaturePolicy(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /** Whether the feature is offered to owners at all. */
    public boolean allowed() {
        return this != FORCED_OFF;
    }

    /** Whether the feature runs regardless of what the owner chose. */
    public boolean forced() {
        return this == FORCED_ON;
    }

    public FeaturePolicy next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /**
     * The next state for a feature that may or may not offer {@code forced-on}.
     * <p>
     * A feature the claim has no switch of its own for cannot meaningfully be "forced on" — it is either
     * offered or it is not. Cycling through a third state that does nothing would just puzzle the admin
     * clicking it, so it is skipped.
     */
    public FeaturePolicy next(boolean forcedOnAvailable) {
        FeaturePolicy candidate = next();
        if (!forcedOnAvailable && candidate == FORCED_ON) {
            return candidate.next();
        }
        return candidate;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Parses a policy from config or a command argument.
     * <p>
     * The plain booleans and {@code on}/{@code off} words are understood too, so the older
     * {@code <feature>.enabled: true} config keys and {@code /claimadmin pantry off} keep working.
     */
    public static Optional<FeaturePolicy> byKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalised) {
            case "ON", "TRUE", "ENABLE", "ENABLED", "YES", "ALLOW", "ALLOWED", "AVAILABLE" ->
                    Optional.of(AVAILABLE);
            case "OFF", "FALSE", "DISABLE", "DISABLED", "NO", "DENY", "DENIED", "FORCED_OFF", "FORCE_OFF" ->
                    Optional.of(FORCED_OFF);
            case "FORCE", "FORCE_ON", "FORCED_ON", "FORCED", "ALWAYS" -> Optional.of(FORCED_ON);
            default -> Optional.empty();
        };
    }

    /** The state a plain boolean maps to, used when reading pre-policy config files. */
    public static FeaturePolicy ofLegacyBoolean(boolean enabled) {
        return enabled ? AVAILABLE : FORCED_OFF;
    }
}
