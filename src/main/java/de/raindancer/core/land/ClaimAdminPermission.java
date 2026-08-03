package de.raindancer.core.land;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/**
 * Management rights a claim owner can hand to a claim admin.
 * <p>
 * Separate from {@link ClaimPermission}: those say what a player may do <em>in</em> the claim, these
 * say what a player may change <em>about</em> the claim.
 */
public enum ClaimAdminPermission {

    MANAGE_MEMBERS("Manage Members", "Add and remove trusted players", Material.PLAYER_HEAD),
    MANAGE_PERMISSIONS("Grant Permissions", "Give or take permissions — limited to the grantable list",
            Material.WRITABLE_BOOK),
    MANAGE_PUBLIC("Manage Public Access", "Change what outsiders may do", Material.OAK_SIGN),
    MANAGE_FLAGS("Manage Flags", "Toggle protection flags", Material.REDSTONE_TORCH),
    MANAGE_BANS("Kick / Ban / Timeout", "Remove players from the claim and bar them from returning",
            Material.IRON_AXE),
    MANAGE_TITLES("Manage Titles", "Edit the enter and leave titles", Material.NAME_TAG),
    MANAGE_ENTRY_FEE("Manage Entry Fee", "Change the toll charged for entering", Material.GOLD_INGOT),
    MANAGE_BANK("Access Bank", "Withdraw collected entry fees", Material.ENDER_CHEST),
    MANAGE_SHAPE("Resize Claim", "Reshape the claim from a selection", Material.GOLDEN_HOE),
    MANAGE_ADMINS("Manage Admins", "Promote and demote other claim admins", Material.BEACON);

    private final String displayName;
    private final String description;
    private final Material icon;

    ClaimAdminPermission(String displayName, String description, Material icon) {
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
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<ClaimAdminPermission> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ClaimAdminPermission permission : values()) {
            if (permission.name().equals(normalised)) {
                return Optional.of(permission);
            }
        }
        return Optional.empty();
    }
}
