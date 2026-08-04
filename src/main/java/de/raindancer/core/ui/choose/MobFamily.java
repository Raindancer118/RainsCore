package de.raindancer.core.ui.choose;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The drawers a creature falls into.
 *
 * <h2>Why the grouping is by name rather than by asking the entity</h2>
 * Because the useful questions — is it hostile, does it swim, is it a boss — are answered by the
 * <em>live</em> entity or by the registry, and neither exists until a server is running. A chooser
 * whose sorting can only be checked on a server is a chooser whose sorting nobody ever checks.
 *
 * <p>So the sorting is a list of names, which is ordinary code with an ordinary test. It is not
 * clever and it does not need to be: the set of creatures in Minecraft changes about twice a year,
 * and anything this does not recognise lands in {@link #OTHER} rather than vanishing — which is the
 * one property that matters, because a chooser that silently drops the mob somebody wants is worse
 * than one that puts it in the wrong drawer.
 */
public enum MobFamily {

    /**
     * The ones that come for you. What a wave is made of, and the first drawer for that reason.
     */
    HOSTILE("Hostile", "ZOMBIE_HEAD", names(
            "zombie", "zombie_villager", "husk", "drowned", "zombified_piglin", "zoglin",
            "skeleton", "stray", "bogged", "wither_skeleton", "phantom",
            "creeper", "spider", "cave_spider", "silverfish", "endermite",
            "enderman", "blaze", "ghast", "happy_ghast", "magma_cube", "slime",
            "witch", "vindicator", "evoker", "pillager", "ravager", "illusioner", "vex",
            "guardian", "elder_guardian", "shulker", "hoglin", "piglin", "piglin_brute",
            "warden", "breeze", "creaking")),

    /** Everything that is minding its own business. */
    PASSIVE("Passive", "WHEAT", names(
            "cow", "mooshroom", "sheep", "pig", "chicken", "rabbit", "horse", "donkey", "mule",
            "llama", "trader_llama", "camel", "sniffer", "armadillo", "goat", "bee",
            "villager", "wandering_trader", "iron_golem", "snow_golem", "cat", "ocelot",
            "wolf", "fox", "parrot", "panda", "polar_bear", "turtle", "frog", "tadpole",
            "strider", "bat", "allay", "axolotl", "mooshroom", "skeleton_horse", "zombie_horse")),

    /** The wet ones, which is a drawer because looking for a squid among the cows is hopeless. */
    AQUATIC("Water", "WATER_BUCKET", names(
            "squid", "glow_squid", "dolphin", "cod", "salmon", "tropical_fish", "pufferfish",
            "tadpole", "axolotl", "turtle", "guardian", "elder_guardian", "drowned")),

    /** The three that are an event rather than a mob. */
    BOSS("Bosses", "DRAGON_HEAD", names("ender_dragon", "wither", "warden", "elder_guardian")),

    /** Arrows, boats, item frames — spawnable, and never what somebody means by "a mob". */
    OBJECT("Objects", "ARMOR_STAND", names(
            "armor_stand", "item_frame", "glow_item_frame", "painting", "boat", "chest_boat",
            "minecart", "chest_minecart", "furnace_minecart", "hopper_minecart", "tnt_minecart",
            "spawner_minecart", "command_block_minecart", "arrow", "spectral_arrow", "trident",
            "snowball", "egg", "ender_pearl", "experience_bottle", "potion", "firework_rocket",
            "fishing_bobber", "leash_knot", "end_crystal", "eye_of_ender", "falling_block",
            "primed_tnt", "tnt", "item", "experience_orb", "lightning_bolt", "fireball",
            "small_fireball", "dragon_fireball", "wither_skull", "shulker_bullet", "llama_spit",
            "evoker_fangs", "area_effect_cloud", "marker", "interaction", "block_display",
            "item_display", "text_display", "ominous_item_spawner", "wind_charge",
            "breeze_wind_charge")),

    /** Anything this does not know about. Never empty-handed, never dropped. */
    OTHER("Everything Else", "SPAWNER", names());

    /** Drawers a wave may be built from. A wave of cows is not a wave. */
    private static final Set<MobFamily> FIGHTABLE = Set.of(HOSTILE, BOSS);

    private final String title;
    private final String icon;
    private final Set<String> members;

    /**
     * The members of one drawer.
     *
     * <p>{@code Set.copyOf} rather than {@code Set.of}, which throws on a repeated entry — and these
     * lists overlap by nature: a drowned is both hostile and aquatic, a turtle is both passive and
     * aquatic. A duplicate inside one list is a slip, and the cost of it must be nothing rather than
     * a class that will not initialise and takes every chooser on the server down with it.
     */
    private static Set<String> names(String... members) {
        return Set.copyOf(List.of(members));
    }

    MobFamily(String title, String icon, Set<String> members) {
        this.title = title;
        this.icon = icon;
        this.members = members;
    }

    public String title() {
        return title;
    }

    /** A material name rather than a {@code Material}: resolving one needs the server's registry. */
    public String icon() {
        return icon;
    }

    /** Whether a wave may be made of these. */
    public boolean fightable() {
        return FIGHTABLE.contains(this);
    }

    /**
     * Which drawer a creature belongs in.
     *
     * <p>Checked in declaration order, so a mob in two sets lands in the first — a drowned is hostile
     * before it is aquatic, because somebody building a wave is looking for it under hostile, and an
     * elder guardian is a boss before it is either.
     */
    public static MobFamily of(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return OTHER;
        }
        String name = entityType.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        // Bosses first, then hostile, then the rest in declaration order — see the note above.
        for (MobFamily family : List.of(BOSS, HOSTILE, AQUATIC, PASSIVE, OBJECT)) {
            if (family.members.contains(name)) {
                return family;
            }
        }
        return OTHER;
    }

    /** {@code CAVE_SPIDER} reads as "Cave spider". */
    public static String readable(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return "Something";
        }
        String words = entityType.trim().toLowerCase(Locale.ROOT)
                .replace("minecraft:", "").replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
