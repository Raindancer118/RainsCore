package de.raindancer.core.world.protection;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a {@link LandFlag} applies to.
 * <p>
 * The three groups are exactly the three standings a player can have on somebody's ground, so every player falls
 * into precisely one of them and no flag lookup can ever come up empty. Whoever owns the ground may want fall
 * damage off for themselves, on for the people they trust and PvP only for strangers — that is what these
 * are for.
 * <p>
 * "Everyone" is deliberately <em>not</em> a constant here: it is not a group somebody can be in, it is a
 * shorthand for editing all three at once. The menus and commands express it as {@code null}.
 */
public enum LandAudience {

    OWNER(Material.GOLDEN_HELMET),
    TRUSTED(Material.IRON_HELMET),
    VISITOR(Material.LEATHER_HELMET);

    private final Material icon;

    LandAudience(Material icon) {
        this.icon = icon;
    }

    /**
     * The message key holding what to call this tier.
     *
     * <p>Not a string in the enum. "You and your co-owners" is true of a claim and false of an arena, and
     * whoever owns the ground knows which — see {@link LandFlag} for the same argument at more length.
     */
    public String nameKey() {
        return "land.audience." + key() + ".name";
    }

    public String descriptionKey() {
        return "land.audience." + key() + ".description";
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
    public static Optional<LandAudience> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT);
        for (LandAudience audience : values()) {
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

    /**
     * Where this player stands on this ground. Never {@code null} — somebody nobody has heard of is a
     * visitor, and unowned ground makes visitors of everybody.
     *
     * <p>Asks the area rather than working it out, because how somebody earns a tier is the area's business:
     * a claim reads its member list, an arena may make everybody a visitor, a plot world goes by plot
     * ownership. Core only needs the three tiers to exist.
     */
    public static LandAudience of(ProtectedArea area, UUID who) {
        if (area == null || who == null) {
            return VISITOR;
        }
        LandAudience standing = area.audienceOf(who);
        return standing == null ? VISITOR : standing;
    }
}
