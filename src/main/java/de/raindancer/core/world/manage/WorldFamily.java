package de.raindancer.core.world.manage;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * A world and the two dimensions named after it — {@code x}, {@code x_nether}, {@code x_the_end}.
 *
 * <h2>Why this exists</h2>
 * Minecraft links dimensions only for the primary level's own folder layout. A world made at runtime
 * has no nether and no end of its own: a portal in it drops the player into the <em>server's</em>
 * nether, and walking back out puts them in the server's overworld. Every plugin that makes worlds at
 * runtime meets this — speedrun found it with a racer who walked out of the race — so the naming and
 * the correction live here once. The suffixes are Minecraft's own, so an owner reading the world
 * folder sees the shape they expect.
 *
 * @param overworld the family's overworld name, as given — Bukkit looks worlds up regardless of case,
 *                  and so does every comparison here
 */
public record WorldFamily(String overworld) {

    public static final String NETHER_SUFFIX = "_nether";
    public static final String END_SUFFIX = "_the_end";

    public WorldFamily {
        overworld = overworld == null ? "" : overworld;
    }

    /** The family {@code worldName} belongs to, whichever of the three it is. */
    public static WorldFamily of(String worldName) {
        if (worldName == null) {
            return new WorldFamily("");
        }
        String lower = worldName.toLowerCase(Locale.ROOT);
        for (String suffix : List.of(END_SUFFIX, NETHER_SUFFIX)) {
            if (lower.endsWith(suffix) && worldName.length() > suffix.length()) {
                return new WorldFamily(worldName.substring(0, worldName.length() - suffix.length()));
            }
        }
        return new WorldFamily(worldName);
    }

    public String nether() {
        return overworld + NETHER_SUFFIX;
    }

    public String theEnd() {
        return overworld + END_SUFFIX;
    }

    /** All three, overworld first — the order they should be made in. */
    public List<String> members() {
        return List.of(overworld, nether(), theEnd());
    }

    /** Whether {@code worldName} is any of the three. */
    public boolean contains(String worldName) {
        return worldName != null && members().stream().anyMatch(worldName::equalsIgnoreCase);
    }

    /** The member for a kind of dimension; empty for a datapack dimension this naming has no member for. */
    public Optional<String> inDimension(World.Environment environment) {
        if (environment == null) {
            return Optional.empty();
        }
        return switch (environment) {
            case NORMAL -> Optional.of(overworld);
            case NETHER -> Optional.of(nether());
            case THE_END -> Optional.of(theEnd());
            default -> Optional.empty();
        };
    }

    /**
     * Where a portal out of {@code from} should really lead, given where the server decided it leads.
     *
     * <p>Only the world is corrected. The coordinates the server worked out are already right for the
     * kind of dimension being entered, and the world swapped in is the same kind, so the scaling holds.
     * Anything that cannot be completed correctly — travel that does not start in this family, a datapack
     * dimension, a member that is not loaded — is left exactly as the server decided: a redirect to
     * nowhere is worse than none.
     *
     * @param lookup a world by name, null when it is not loaded — {@code Bukkit::getWorld} on a server
     * @return the corrected destination, or empty to leave the event alone
     */
    public Optional<Location> redirect(World from, Location to, Function<String, World> lookup) {
        if (from == null || to == null || lookup == null) {
            return Optional.empty();
        }
        World toWorld;
        try {
            toWorld = to.getWorld();
        } catch (IllegalArgumentException unloaded) {
            // A Location in a world that has since been unloaded throws rather than answering null.
            return Optional.empty();
        }
        if (toWorld == null || !contains(from.getName())) {
            return Optional.empty();
        }
        Optional<String> wanted = inDimension(toWorld.getEnvironment());
        if (wanted.isEmpty() || wanted.get().equalsIgnoreCase(toWorld.getName())) {
            return Optional.empty();
        }
        World destination = lookup.apply(wanted.get());
        if (destination == null) {
            return Optional.empty();
        }
        Location corrected = to.clone();
        corrected.setWorld(destination);
        return Optional.of(corrected);
    }
}
