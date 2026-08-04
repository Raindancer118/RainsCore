package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Putting an ore vein into the world.
 *
 * <h2>What it refuses to bury, and why that is the whole design</h2>
 * Only ground it recognises as natural: stone, deepslate, netherrack, dirt, sand and the rest of what
 * a world generates. Never a placed block, never air, never a chest.
 *
 * <p>That is not politeness. This is a tool a moderator points at the ground while standing in
 * somebody's build, and the version that replaces whatever is there is the version that eats a wall of
 * a player's house and cannot put it back — there is no undo here, and the person who lost the wall
 * was not in the room. Refusing to touch anything a player could have placed makes the worst outcome
 * "nothing happened", which is a complaint rather than a disaster.
 *
 * <p>The same rule makes it usable: a vein dropped into a cave fills the stone around the cave rather
 * than hanging in the air, which is what somebody aiming at a cave wall meant.
 */
public final class Veins {

    /**
     * What a vein may replace.
     *
     * <p>Written out rather than derived from a tag, because the tags that look right are not:
     * {@code BASE_STONE_OVERWORLD} misses gravel and dirt, and asking a tag needs the registry, which
     * needs a server. This list is what world generation actually leaves lying about.
     */
    private static final Set<String> NATURAL = Set.of(
            "STONE", "COBBLESTONE", "DEEPSLATE", "COBBLED_DEEPSLATE", "TUFF", "CALCITE",
            "GRANITE", "DIORITE", "ANDESITE", "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "GRAVEL",
            "SAND", "RED_SAND", "SANDSTONE", "RED_SANDSTONE", "CLAY", "MUD", "SNOW_BLOCK",
            "NETHERRACK", "BASALT", "BLACKSTONE", "SOUL_SAND", "SOUL_SOIL", "MAGMA_BLOCK",
            "END_STONE", "TERRACOTTA", "PACKED_ICE", "BLUE_ICE", "ICE",
            // The ores themselves, so a second vein can be laid over a first — otherwise placing one
            // in a naturally ore-rich patch leaves holes wherever the world got there first.
            "COAL_ORE", "IRON_ORE", "COPPER_ORE", "GOLD_ORE", "REDSTONE_ORE", "LAPIS_ORE",
            "DIAMOND_ORE", "EMERALD_ORE", "NETHER_QUARTZ_ORE", "NETHER_GOLD_ORE",
            "DEEPSLATE_COAL_ORE", "DEEPSLATE_IRON_ORE", "DEEPSLATE_COPPER_ORE",
            "DEEPSLATE_GOLD_ORE", "DEEPSLATE_REDSTONE_ORE", "DEEPSLATE_LAPIS_ORE",
            "DEEPSLATE_DIAMOND_ORE", "DEEPSLATE_EMERALD_ORE");

    private final Ground ground;

    public Veins(Ground ground) {
        this.ground = ground;
    }

    /** What happened, in the words a message needs. */
    public record Placed(int blocks, int skipped) {

        /** Whether anything at all was buried. */
        public boolean isEmpty() {
            return blocks == 0;
        }
    }

    /**
     * Buries {@code vein} as {@code ore}, skipping anything it may not replace.
     *
     * <p>Deepslate is handled per block rather than per vein: a vein that straddles the boundary comes
     * out as ore above and deepslate ore below, which is what the world itself does, and the version
     * that picks one for the whole vein leaves a slab of the wrong stone visible from a cave.
     */
    public Placed place(OreVein vein, String ore) {
        String wanted = ore == null ? "" : ore.trim().toUpperCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            return new Placed(0, vein.size());
        }
        int placed = 0;
        int skipped = 0;
        for (Spot spot : vein.blocks()) {
            if (!ground.isLoaded(spot)) {
                skipped++;
                continue;
            }
            String there = ground.materialAt(spot);
            if (there == null || !NATURAL.contains(there.toUpperCase(Locale.ROOT))) {
                skipped++;
                continue;
            }
            if (ground.set(spot, variantFor(wanted, there))) {
                placed++;
            } else {
                skipped++;
            }
        }
        return new Placed(placed, skipped);
    }

    /**
     * The deepslate form of an ore when it is being buried in deepslate.
     *
     * <p>Only where one exists — there is no {@code DEEPSLATE_NETHER_QUARTZ_ORE}, and asking for one
     * would place nothing at all.
     */
    static String variantFor(String ore, String replacing) {
        boolean deep = replacing.toUpperCase(Locale.ROOT).contains("DEEPSLATE");
        if (!deep || ore.startsWith("DEEPSLATE_")) {
            return ore;
        }
        String deepslate = "DEEPSLATE_" + ore;
        return HAS_DEEPSLATE_FORM.contains(deepslate) ? deepslate : ore;
    }

    private static final Set<String> HAS_DEEPSLATE_FORM = Set.of(
            "DEEPSLATE_COAL_ORE", "DEEPSLATE_IRON_ORE", "DEEPSLATE_COPPER_ORE",
            "DEEPSLATE_GOLD_ORE", "DEEPSLATE_REDSTONE_ORE", "DEEPSLATE_LAPIS_ORE",
            "DEEPSLATE_DIAMOND_ORE", "DEEPSLATE_EMERALD_ORE");

    /** What a vein may be made of, for a chooser that should not offer a jukebox. */
    public static List<String> ores() {
        return List.of("COAL_ORE", "IRON_ORE", "COPPER_ORE", "GOLD_ORE", "REDSTONE_ORE",
                "LAPIS_ORE", "DIAMOND_ORE", "EMERALD_ORE", "NETHER_QUARTZ_ORE", "NETHER_GOLD_ORE",
                "ANCIENT_DEBRIS");
    }

    /** Whether this is ground a vein would be willing to replace. For a screen that greys a button. */
    public static boolean isNatural(String material) {
        return material != null && NATURAL.contains(material.trim().toUpperCase(Locale.ROOT));
    }
}
