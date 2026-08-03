package de.raindancer.core.world.protection;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The flags, in groups somebody can find something in.
 *
 * <p>Twenty-six toggles on one paginated page is a wall: you read all of them to find the one you want, and the
 * one you want is on page three. Grouped, an owner looking for "stop the creepers" opens <em>Mobs</em> and reads
 * five.
 *
 * <p>Here rather than in a menu class because the grouping is a property of the flags, and a second plugin
 * showing them — an arena, a plot world — should not have to invent its own arrangement or end up with a
 * different one. Which group a flag is in is decided in one place, and a flag that is added and forgotten shows
 * up in {@link #OTHER} rather than vanishing from the screens.
 */
public enum LandFlagGroup {

    /** Who may hurt whom, and what hurts them. */
    COMBAT(Material.DIAMOND_SWORD,
            LandFlag.PVP, LandFlag.MOB_DAMAGE, LandFlag.EXPLOSION_DAMAGE, LandFlag.FALL_DAMAGE,
            LandFlag.HUNGER, LandFlag.POTIONS),

    /** What the ground and the weather do on their own. */
    NATURE(Material.OAK_SAPLING,
            LandFlag.FIRE_SPREAD, LandFlag.LEAF_DECAY, LandFlag.SNOW_ICE_FORM, LandFlag.EXPLOSIONS),

    /** Creatures: where they appear, whether they come in, and whether they can break things. */
    MOBS(Material.ZOMBIE_HEAD,
            LandFlag.MONSTER_SPAWNING, LandFlag.ANIMAL_SPAWNING, LandFlag.SPAWNER_SPAWNING,
            LandFlag.MONSTER_ENTRY, LandFlag.MONSTER_TARGETING, LandFlag.MOB_GRIEF,
            LandFlag.ENDERMAN_GRIEF),

    /** Ways in and out that are not walking. */
    TRAVEL(Material.ENDER_PEARL,
            LandFlag.TELEPORT_IN, LandFlag.ENDER_PEARL_IN, LandFlag.ELYTRA_FLIGHT, LandFlag.RIPTIDE),

    /** What somebody outside the border can reach in with. */
    BORDER(Material.PISTON,
            LandFlag.PISTONS_FROM_OUTSIDE, LandFlag.FLUIDS_FROM_OUTSIDE),

    /** What happens to somebody's things when they die here. */
    DEATH(Material.TOTEM_OF_UNDYING,
            LandFlag.KEEP_INVENTORY, LandFlag.ITEM_DROPS),

    /**
     * Anything not filed anywhere else.
     *
     * <p>Exists so a flag added without being grouped still appears on a screen. A flag nobody can see is worse
     * than an untidy group, and {@code LandFlagGroupTest} fails when this is not empty — so the untidiness is
     * loud rather than permanent.
     */
    OTHER(Material.BARRIER);

    private final Material icon;
    private final List<LandFlag> flags;

    LandFlagGroup(Material icon, LandFlag... flags) {
        this.icon = icon;
        this.flags = List.of(flags);
    }

    public Material icon() {
        return icon;
    }

    /** The message key holding what to call this group. Wording is never in the enum — see {@link LandFlag}. */
    public String nameKey() {
        return "land.flag-group." + key() + ".name";
    }

    public String descriptionKey() {
        return "land.flag-group." + key() + ".description";
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The flags filed here, in the order they are shown. */
    public List<LandFlag> flags() {
        return this == OTHER ? ungrouped() : flags;
    }

    /**
     * Which group a flag belongs to. Never null: anything unfiled is {@link #OTHER}.
     */
    public static LandFlagGroup of(LandFlag flag) {
        for (LandFlagGroup group : values()) {
            if (group != OTHER && group.flags.contains(flag)) {
                return group;
            }
        }
        return OTHER;
    }

    /** Flags nobody has filed. Empty on a tidy day; see the note on {@link #OTHER}. */
    public static List<LandFlag> ungrouped() {
        List<LandFlag> loose = new ArrayList<>();
        for (LandFlag flag : LandFlag.values()) {
            if (of(flag) == OTHER) {
                loose.add(flag);
            }
        }
        return List.copyOf(loose);
    }

    /** The groups that have something in them, which is what a screen lists. */
    public static List<LandFlagGroup> occupied() {
        List<LandFlagGroup> shown = new ArrayList<>();
        for (LandFlagGroup group : values()) {
            if (!group.flags().isEmpty()) {
                shown.add(group);
            }
        }
        return List.copyOf(shown);
    }
}
