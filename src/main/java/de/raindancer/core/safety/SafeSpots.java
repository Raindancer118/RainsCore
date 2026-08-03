package de.raindancer.core.safety;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Whether it is safe to put a player somewhere, and where to put them instead.
 *
 * <h2>Why this exists</h2>
 * Because every plugin that moves a player needs it and each of them had half of it. A warp on a
 * platform that has since been mined, a home in a house somebody flooded, a farm world regenerated
 * under somebody's bed, a ghast line whose landing pad is now a lava pool — all of them end with a
 * player suffocating in stone or falling out of the sky, and the plugin that put them there had no
 * idea it had. "Is the world loaded" was as far as most of them got.
 *
 * <h2>What safe means here</h2>
 * Two blocks of room for the player, something solid under their feet, and none of lava, fire,
 * cactus, magma, a portal or an unloaded chunk in the way — plus a drop short enough to survive.
 * Water is refused by default and can be allowed, because "survivable" and "somewhere to arrive" are
 * not the same thing.
 *
 * <p>What it does <em>not</em> do is guess at anything it cannot see: it says nothing about mobs,
 * claims, or whether somebody is waiting there with a sword. Those belong to the plugins that know
 * about them, and a class that pretended to answer them would be trusted for answers it does not
 * have.
 *
 * <h2>Chunks</h2>
 * A scan never loads a chunk. Generating terrain to answer "is this safe" turns one player's warp
 * into a freeze for everybody on the server, so a search that runs out of loaded ground gives up and
 * says so instead.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread as far as this class is concerned; whether {@link Blocks} is depends on the
 * implementation, and the Bukkit one must be asked on the right thread for its region.
 */
public final class SafeSpots {

    /** How far a player can fall without being hurt. Vanilla is three; this leaves a little room. */
    private static final int SAFE_DROP = 3;

    /** How far down a search looks for ground before deciding there is none. */
    private static final int LOOK_DOWN = 96;

    private final Blocks blocks;

    private volatile boolean allowWater;
    private volatile int surroundingRadius;

    public SafeSpots(Blocks blocks) {
        this.blocks = blocks;
    }

    /**
     * Whether arriving underwater counts as safe.
     *
     * <p>Off by default. On for a warp somebody deliberately put in an ocean monument, where
     * refusing it would be this class overruling the person who set it.
     */
    public void allowWater(boolean allowWater) {
        this.allowWater = allowWater;
    }

    public boolean isAllowingWater() {
        return allowWater;
    }

    /**
     * How far around a spot to look for something dangerous.
     *
     * <p>Zero — nothing — by default, because this is a judgement rather than a fact. A warp with a
     * decorative campfire beside it is fine, and a class that refused it would be overruling the
     * person who placed it. But arriving one block from a lava lake is somewhere a player turns
     * around and dies, having just been told the spot was safe, so a caller who wants the stricter
     * question has to be able to ask for it.
     *
     * <p>Costs {@code (2r+1)² × 2} block lookups per spot checked, which is why a search with this
     * on wants a smaller radius than one without.
     */
    public void surroundingRadius(int blocks) {
        this.surroundingRadius = Math.max(0, blocks);
    }

    public int surroundingRadius() {
        return surroundingRadius;
    }

    // ---------------------------------------------------------------------------- judging

    /** Whether a player can be put here. */
    public boolean isSafe(Spot spot) {
        return check(spot).isSafe();
    }

    /**
     * What is wrong with a spot, or {@link Danger#NONE}.
     *
     * <p>The order of these checks is the order a player would notice them, which is also roughly
     * the order of how badly they end.
     */
    public Danger check(Spot spot) {
        if (spot == null) {
            return Danger.OUT_OF_THE_WORLD;
        }
        Spot head = spot.offset(0, 1, 0);
        if (spot.y() < blocks.lowestY() || head.y() >= blocks.highestY()) {
            return Danger.OUT_OF_THE_WORLD;
        }
        if (!blocks.isLoaded(spot)) {
            return Danger.NOT_LOADED;
        }

        BlockKind feet = blocks.at(spot);
        BlockKind above = blocks.at(head);
        BlockKind below = blocks.at(spot.offset(0, -1, 0));

        Danger inTheWay = whatIsWrongWith(feet);
        if (inTheWay != Danger.NONE) {
            return inTheWay;
        }
        Danger overhead = whatIsWrongWith(above);
        if (overhead != Danger.NONE) {
            return overhead;
        }

        // Underfoot is judged differently: water down there is something to land in, and only the
        // things that hurt on contact matter.
        if (below == BlockKind.LAVA) {
            return Danger.LAVA;
        }
        if (below == BlockKind.HARMFUL) {
            return Danger.HURTS;
        }
        if (!below.canStandOn()) {
            Danger drop = howFarDown(spot);
            if (!drop.isSafe()) {
                return drop;
            }
        }
        // Last, so that what is wrong with the spot itself is always what gets said. "You are
        // inside a block" is more use than "there is lava nearby" when both are true.
        return whatIsAround(spot);
    }

    /**
     * What is dangerous near a spot, when anybody asked.
     *
     * <p>An unloaded neighbour is not held against it: refusing every spot beside a chunk nobody has
     * loaded would refuse most of the edge of the loaded world, which is not a safety rule so much
     * as a way of failing at random.
     */
    private Danger whatIsAround(Spot spot) {
        int radius = surroundingRadius;
        if (radius <= 0) {
            return Danger.NONE;
        }
        Danger worst = Danger.NONE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    Spot near = spot.offset(dx, dy, dz);
                    if (!blocks.isLoaded(near)) {
                        continue;
                    }
                    BlockKind kind = blocks.at(near);
                    if (kind == BlockKind.LAVA) {
                        // Nothing beats lava, so there is no point looking further.
                        return Danger.LAVA_NEARBY;
                    }
                    if (kind == BlockKind.HARMFUL) {
                        worst = Danger.HURTS_NEARBY;
                    }
                }
            }
        }
        return worst;
    }

    /** What is wrong with a block a player would be standing inside. */
    private Danger whatIsWrongWith(BlockKind kind) {
        return switch (kind) {
            case PASSABLE -> Danger.NONE;
            case WATER -> allowWater ? Danger.NONE : Danger.UNDERWATER;
            case LAVA -> Danger.LAVA;
            case HARMFUL -> Danger.HURTS;
            case PORTAL -> Danger.PORTAL;
            case SOLID, UNKNOWN -> Danger.INSIDE_A_BLOCK;
        };
    }

    /**
     * Whether the drop from here is survivable.
     *
     * <p>Floating in the air is not automatically wrong — a spot two blocks above the ground is
     * where a player lands from a doorstep — but a hundred is, and the difference has to be checked
     * rather than assumed either way.
     */
    private Danger howFarDown(Spot spot) {
        int bottom = Math.max(blocks.lowestY(), spot.y() - LOOK_DOWN);
        for (int y = spot.y() - 1; y >= bottom; y--) {
            Spot below = spot.atHeight(y);
            if (!blocks.isLoaded(below)) {
                return Danger.NOT_LOADED;
            }
            BlockKind kind = blocks.at(below);
            if (kind == BlockKind.LAVA || kind == BlockKind.HARMFUL) {
                return kind == BlockKind.LAVA ? Danger.LAVA : Danger.HURTS;
            }
            if (kind.canStandOn() || kind == BlockKind.WATER) {
                int drop = spot.y() - y - 1;
                return drop <= SAFE_DROP || kind == BlockKind.WATER
                        ? Danger.NONE : Danger.A_LONG_WAY_DOWN;
            }
        }
        return Danger.NOTHING_BELOW;
    }

    // ---------------------------------------------------------------------------- searching

    /**
     * The nearest spot a player can be put, starting from this one.
     *
     * <p>Looks in the column first, because a player stuck in a wall wants to be on top of that wall
     * and not thirty blocks east; then outwards, nearest first. Empty means nowhere within reach was
     * safe — which callers must treat as a refusal rather than falling back to the original spot,
     * since the original spot is the one already known to be dangerous.
     *
     * @param radius how far sideways to look; 0 checks only the spot given
     */
    public Optional<Spot> nearestSafe(Spot from, int radius) {
        if (from == null) {
            return Optional.empty();
        }
        if (isSafe(from)) {
            return Optional.of(from);
        }
        if (radius <= 0) {
            // No searching at all. A caller asking for zero wants a yes or a no about this spot,
            // not to be quietly moved somewhere else.
            return Optional.empty();
        }
        Optional<Spot> inColumn = safeInColumn(from);
        if (inColumn.isPresent()) {
            return inColumn;
        }

        // Outwards ring by ring, so the first ring with anything in it is the nearest — rather than
        // scanning a whole square and sorting, which on a radius of 32 is four thousand columns.
        for (int ring = 1; ring <= radius; ring++) {
            List<Spot> found = new ArrayList<>();
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    safeInColumn(from.offset(dx, 0, dz)).ifPresent(found::add);
                }
            }
            if (!found.isEmpty()) {
                return found.stream().min(Comparator.comparingLong(from::distanceSquaredTo));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a player put here would be standing on something rather than falling.
     *
     * <p>Stricter than {@link #isSafe}, and deliberately so. A spot three blocks above the floor is
     * safe — it is where a player lands off a doorstep — but it is not somewhere to <em>put</em>
     * anybody: a rescue that drops somebody from the air, even survivably, reads as a bug. So
     * judging a spot and choosing a spot are allowed to differ, and this is the difference.
     */
    private boolean isStandingSpot(Spot spot) {
        if (!isSafe(spot)) {
            return false;
        }
        BlockKind below = blocks.at(spot.offset(0, -1, 0));
        return below.canStandOn() || (allowWater && below == BlockKind.WATER);
    }

    /**
     * The spot in one column, closest to the height asked for, where a player would be standing.
     *
     * <p>Up and down together, a step at a time, so "closest" means closest rather than "the first
     * one going up". A player pushed out of a floor should end up on it, not on the roof.
     */
    private Optional<Spot> safeInColumn(Spot around) {
        if (!blocks.isLoaded(around)) {
            return Optional.empty();
        }
        if (isStandingSpot(around)) {
            return Optional.of(around);
        }
        int up = around.y();
        int down = around.y();
        int ceiling = blocks.highestY() - 2;
        int floor = blocks.lowestY();
        while (up < ceiling || down > floor) {
            if (++up < ceiling && isStandingSpot(around.atHeight(up))) {
                return Optional.of(around.atHeight(up));
            }
            if (--down > floor && isStandingSpot(around.atHeight(down))) {
                return Optional.of(around.atHeight(down));
            }
        }
        return Optional.empty();
    }
}
