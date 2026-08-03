package de.raindancer.core.land;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/** The currency used for claim creation costs and claim entry fees. */
public enum CostType {

    NONE("Free", "No cost at all", Material.BARRIER),
    ITEM("Item", "A configurable item stack", Material.NETHER_STAR),
    XP_LEVELS("XP Levels", "Experience levels", Material.EXPERIENCE_BOTTLE),
    XP_POINTS("XP Points", "Raw experience points", Material.EXPERIENCE_BOTTLE);

    private final String displayName;
    private final String description;
    private final Material icon;

    CostType(String displayName, String description, Material icon) {
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public Material icon() {
        return icon;
    }

    public CostType next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<CostType> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        // Friendly aliases so /claimadmin cost xp works as people expect.
        if (normalised.equals("XP") || normalised.equals("LEVEL") || normalised.equals("LEVELS")) {
            return Optional.of(XP_LEVELS);
        }
        if (normalised.equals("POINTS")) {
            return Optional.of(XP_POINTS);
        }
        if (normalised.equals("OFF") || normalised.equals("FREE") || normalised.equals("DISABLED")) {
            return Optional.of(NONE);
        }
        for (CostType type : values()) {
            if (type.name().equals(normalised)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
