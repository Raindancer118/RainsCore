package de.raindancer.core.ui.identity;

import org.bukkit.scoreboard.Team;

/**
 * Whether Core's own scoreboard teams hide the vanilla name above heads — on while {@link Nametags}
 * draws its own, so a player never shows two. Read by the tablist's sorting teams as well, which are
 * the teams most players are in.
 */
public final class VanillaNametags {

    private static volatile boolean hidden;

    private VanillaNametags() {
    }

    public static boolean hidden() {
        return hidden;
    }

    static void hidden(boolean hide) {
        hidden = hide;
    }

    /** Brings one of Core's teams in line. Cheap when it already is. */
    public static void applyTo(Team team) {
        Team.OptionStatus wanted = hidden ? Team.OptionStatus.NEVER : Team.OptionStatus.ALWAYS;
        if (team.getOption(Team.Option.NAME_TAG_VISIBILITY) != wanted) {
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, wanted);
        }
    }
}
