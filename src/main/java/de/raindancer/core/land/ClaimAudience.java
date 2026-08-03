package de.raindancer.core.land;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a {@link ClaimFlag} applies to.
 * <p>
 * The three groups are exactly the three standings a player can have in a claim, so every player falls
 * into precisely one of them and no flag lookup can ever come up empty. A claim owner may want fall
 * damage off for themselves, on for the people they trust and PvP only for strangers — that is what these
 * are for.
 * <p>
 * "Everyone" is deliberately <em>not</em> a constant here: it is not a group somebody can be in, it is a
 * shorthand for editing all three at once. The menus and commands express it as {@code null}.
 */
public enum ClaimAudience {

    OWNER("Owners", "You and your co-owners", Material.GOLDEN_HELMET),
    TRUSTED("Trusted", "Everyone you have trusted", Material.IRON_HELMET),
    VISITOR("Visitors", "Everybody else", Material.LEATHER_HELMET);

    private final String displayName;
    private final String description;
    private final Material icon;

    ClaimAudience(String displayName, String description, Material icon) {
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

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses an audience name. {@code everyone}/{@code all} resolve to an empty optional on purpose —
     * that is the "all three at once" shorthand, not a group.
     */
    public static Optional<ClaimAudience> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT);
        for (ClaimAudience audience : values()) {
            if (audience.name().equals(normalised)) {
                return Optional.of(audience);
            }
        }
        // A few spellings players reach for that are not the enum name.
        return switch (normalised) {
            case "OWNERS", "SELF", "ME" -> Optional.of(OWNER);
            case "TRUSTED-PLAYERS", "MEMBER", "MEMBERS", "FRIENDS" -> Optional.of(TRUSTED);
            case "VISITORS", "GUEST", "GUESTS", "PUBLIC", "STRANGERS" -> Optional.of(VISITOR);
            default -> Optional.empty();
        };
    }

    /** True for the words meaning "all three groups", which every command accepts in place of a group. */
    public static boolean isEveryone(String raw) {
        if (raw == null) {
            return false;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "everyone", "everybody", "all", "*" -> true;
            default -> false;
        };
    }

    /** Where this player stands in the claim. Never {@code null} — a stranger is a visitor. */
    public static ClaimAudience of(Claim claim, UUID uuid) {
        if (claim == null || uuid == null) {
            return VISITOR;
        }
        if (claim.isOwner(uuid)) {
            return OWNER;
        }
        return claim.member(uuid).isPresent() ? TRUSTED : VISITOR;
    }
}
