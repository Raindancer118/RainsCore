package de.raindancer.core.ui.choose;

import java.util.List;
import java.util.Locale;

/**
 * The drawers particles are sorted into.
 *
 * <p>By what a particle is <em>for</em> rather than by its name, because the names do not sort:
 * {@code CRIT}, {@code DUST_PLUME}, {@code SCULK_CHARGE_POP} and {@code TRIAL_SPAWNER_DETECTION} have
 * nothing in common alphabetically and everything in common in use.
 */
public enum ParticleGroup {

    FIRE("Fire & Smoke", "CAMPFIRE"),
    WATER("Water", "WATER_BUCKET"),
    MAGIC("Magic", "ENCHANTING_TABLE"),
    EMOTES("Moods", "POPPY"),
    COMBAT("Combat", "IRON_SWORD"),
    WEATHER("Weather & Air", "SNOWBALL"),
    /** The ones that need a colour or a block to mean anything — see {@code needsExtraData}. */
    COLOURED("Coloured Dust", "RED_DYE"),
    BLOCKS("Blocks & Items", "GRASS_BLOCK"),
    OTHER("Everything Else", "GLASS");

    private final String title;
    private final String icon;

    ParticleGroup(String title, String icon) {
        this.title = title;
        this.icon = icon;
    }

    public String title() {
        return title;
    }

    /** The material to draw the group with, by name. */
    public String icon() {
        return icon;
    }

    /** Which drawer a particle belongs in. Never null. */
    public static ParticleGroup of(String particle) {
        return ParticleCatalogue.groupOf(particle);
    }

    static List<ParticleGroup> ordered() {
        return List.of(values());
    }

    static String normalise(String particle) {
        return particle == null ? "" : particle.trim().toUpperCase(Locale.ROOT);
    }
}
