package de.raindancer.core.warp;

import de.raindancer.core.poi.Poi;

import java.util.Optional;

/**
 * A warp: somewhere with a name that a player can be sent to.
 *
 * <h2>Why this wraps a {@link Poi} rather than replacing it</h2>
 * A warp <em>is</em> a place with a name, a world and coordinates — which is what a POI is, down to
 * the "the world is a name, not a World" reasoning and the handling of a world that is not loaded.
 * Writing that twice would mean two answers to every one of those questions and two files to keep in
 * step. So the place is the POI and this adds only what a warp actually has on top of one: who may
 * use it and what it is filed under.
 *
 * <p>The practical payoff is that everything which already understands places understands warps: a
 * ghast line can fly to one, a menu can list them beside homes, and deleting a world takes its warps
 * with it — none of which would work if a warp were a second store that happened to look the same.
 */
public record Warp(Poi poi) {

    /** Where a warp's permission is kept on the underlying place. */
    static final String TAG_PERMISSION = "permission";
    /** Where its category is kept. */
    static final String TAG_CATEGORY = "category";

    public String name() {
        return poi.name();
    }

    public String world() {
        return poi.world();
    }

    /** What a menu shows: the label its creator gave it, or its name. */
    public String label() {
        return poi.label();
    }

    /** The permission needed to use it, or empty when anybody may. */
    public Optional<String> permission() {
        return poi.tag(TAG_PERMISSION);
    }

    /** What it is filed under, for a menu that groups them. */
    public Optional<String> category() {
        return poi.tag(TAG_CATEGORY);
    }

    /** Whether the world it is in is loaded right now. */
    public boolean isReachable() {
        return poi.isReachable();
    }

    public String coordinates() {
        return poi.coordinates();
    }
}
