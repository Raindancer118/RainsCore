package de.raindancer.core.world.manage;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an owner set up on a world that is not in its terrain: game rules, difficulty, the border, and
 * where it spawns people — read before a regeneration deletes it, and put back on the world that
 * replaces it.
 *
 * <h2>Why</h2>
 * Everything here lives in the world's own {@code level.dat}, which goes with the folder. The first
 * thing anybody running a world manager hears after a reset is "it rains again, creepers blow holes in
 * the spawn again, and the border is gone" — so it is carried across rather than rediscovered.
 *
 * <h2>The spawn point, only on the same map</h2>
 * Coordinates that were a good spawn on one seed are an ocean or a mountain on another, so the spawn is
 * only restored when the new world is the same map. On a new seed the server's own choice of spawn is
 * the better one.
 *
 * <h2>Game rules as text</h2>
 * Through the name/value form rather than typed {@code GameRule}s: the rule set changes between
 * Minecraft versions, and a snapshot that only knew the rules of one version would silently drop the
 * rest on the next.
 */
public record WorldSnapshot(Map<String, String> gameRules, Difficulty difficulty,
                            double borderCenterX, double borderCenterZ, double borderSize,
                            double spawnX, double spawnY, double spawnZ, float spawnYaw, boolean hasSpawn) {

    /** Vanilla's own border size — a world still at it has no border worth writing back. */
    public static final double VANILLA_BORDER = 5.9999968E7;

    private static final LogChannel log = Log.of("world");

    public WorldSnapshot {
        gameRules = gameRules == null ? Map.of() : Map.copyOf(gameRules);
    }

    /** Reads it off a still-loaded world. Anything the world does not answer is simply not carried. */
    public static WorldSnapshot of(World world) {
        if (world == null) {
            return new WorldSnapshot(Map.of(), null, 0, 0, VANILLA_BORDER, 0, 0, 0, 0f, false);
        }
        Map<String, String> rules = new LinkedHashMap<>();
        String[] names = world.getGameRules();
        if (names != null) {
            for (String name : names) {
                String value = world.getGameRuleValue(name);
                if (value != null) {
                    rules.put(name, value);
                }
            }
        }
        double centerX = 0;
        double centerZ = 0;
        double size = VANILLA_BORDER;
        WorldBorder border = world.getWorldBorder();
        if (border != null && border.getCenter() != null) {
            centerX = border.getCenter().getX();
            centerZ = border.getCenter().getZ();
            size = border.getSize();
        }
        Location spawn = world.getSpawnLocation();
        return new WorldSnapshot(rules, world.getDifficulty(), centerX, centerZ, size,
                spawn == null ? 0 : spawn.getX(), spawn == null ? 0 : spawn.getY(),
                spawn == null ? 0 : spawn.getZ(), spawn == null ? 0f : spawn.getYaw(), spawn != null);
    }

    /**
     * Puts it back on {@code world}. One setting refusing does not stop the rest.
     *
     * @param sameMap whether {@code world} was generated from the same seed — the spawn point is only
     *                restored when it was
     */
    public void applyTo(World world, boolean sameMap) {
        if (world == null) {
            return;
        }
        gameRules.forEach((name, value) -> {
            try {
                world.setGameRuleValue(name, value);
            } catch (RuntimeException refused) {
                log.warn("Game rule {} could not be carried over to '{}'.", name, world.getName());
            }
        });
        if (difficulty != null) {
            world.setDifficulty(difficulty);
        }
        WorldBorder border = world.getWorldBorder();
        if (border != null && borderSize < VANILLA_BORDER) {
            border.setCenter(borderCenterX, borderCenterZ);
            border.setSize(borderSize);
        }
        if (sameMap && hasSpawn) {
            world.setSpawnLocation(new Location(world, spawnX, spawnY, spawnZ, spawnYaw, 0f));
        }
    }
}
