package de.raindancer.core.moderation.vanish;

import org.bukkit.entity.Player;

/** Who may see vanished players: the see-vanished permission, or staff while staff-see-staff is on. */
public final class VanishSight {

    private VanishSight() {
    }

    public static boolean sees(Player player, Vanish vanish, String seeVanishedPermission) {
        if (seeVanishedPermission != null && player.hasPermission(seeVanishedPermission)) {
            return true;
        }
        if (!vanish.isStaffSeeStaff()) {
            return false;
        }
        for (String node : vanish.staffNodes()) {
            if (player.hasPermission(node)) {
                return true;
            }
        }
        return false;
    }
}
