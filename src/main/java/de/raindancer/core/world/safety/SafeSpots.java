package de.raindancer.core.world.safety;

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

    /**
     * The longest {@link #nearestConsistentHeight} is allowed to spend looking, however bad the
     * terrain turns out to be.
     *
     * <p>The heightmap seeding makes ordinary terrain fast, but "ordinary" is not a guarantee: a
     * search radius of thirty-two over honeycombed, freshly-generated, or deliberately adversarial
     * terrain can still visit a great many columns, and each one that falls back to the unbounded
     * walk costs what it always cost. A player waiting on a teleport should get an answer in a
     * couple of seconds even when this class has done its best and the terrain has not cooperated —
     * the best spot found so far beats a search that quietly keeps going, and it beats a refusal
     * even more.
     */
    private static final long SEARCH_TIME_BUDGET_NANOS = java.time.Duration.ofSeconds(2).toNanos();

    private final Blocks blocks;

    private volatile boolean allowWater;
    private volatile int surroundingRadius;
    private volatile boolean naturalGroundOnly;

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

    /**
     * Whether a spot to actually <em>land</em> on must be the terrain itself — stone, dirt, sand and
     * the like — rather than a tree, a roof, or anything else merely solid.
     *
     * <p>Off by default, for the same reason {@link #allowWater} is: a warp somebody placed on a
     * wooden platform is exactly as much "somewhere to arrive" as one on grass, and a class that
     * refused it would be overruling the person who set it. A <em>scattered</em> arrival is different
     * — nobody chose that log at the top of a tree, it is just the first solid thing the search fell
     * onto, and a player who wanted a random spot in the world did not mean the inside of its
     * canopy. Checked only where {@link #isStandingSpot} looks, not by {@link #isSafe}: whether a
     * spot judged dangerous is safe is a fact about the block, and asking that question a stricter
     * way would answer a different question than the one it was asked.
     */
    public void naturalGroundOnly(boolean naturalGroundOnly) {
        this.naturalGroundOnly = naturalGroundOnly;
    }

    public boolean isNaturalGroundOnly() {
        return naturalGroundOnly;
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
        Spot underfoot = spot.offset(0, -1, 0);
        // Below the bottom of the world is not "unknown, so probably solid" — it is the one place
        // that answer is actually wrong, because there is nothing there to be wrong about. See
        // BlockKind#UNKNOWN: everywhere else, "cannot be checked" earns the benefit of the doubt;
        // one block past the floor, it is the void asking for it.
        BlockKind below = underfoot.y() < blocks.lowestY() ? BlockKind.PASSABLE : blocks.at(underfoot);

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
     * The nearest spot whose height roughly agrees with its own surroundings — never the bottom of an
     * isolated pit or the mouth of a cave that happens to be technically safe.
     *
     * <h2>The problem this solves</h2>
     * {@link #nearestSafe} accepts the first standing spot it finds in the exact column asked for,
     * and the bottom of a ravine is a perfectly good standing spot by that definition: solid ground
     * underfoot, clear air above. Asked for from high above — which is how a scattered arrival finds
     * its landing column in the first place — the search comes straight down and stops there, and a
     * player who was meant to land on the surface ends up ten metres under it instead, in a hole
     * nobody standing nearby would call "here".
     *
     * <p>This asks one more question before accepting a spot: does the ground immediately beside it
     * sit at roughly the same height? A ravine's neighbours do not; ordinary ground's neighbours do.
     * Checked at four points a block away, and only within a narrow window of the candidate's own
     * height — see {@link #agreesWithNeighbours} for why that bound is not an optimisation so much
     * as the difference between this being usable and this taking the better part of a minute.
     *
     * @param heightTolerance how many blocks a spot's height may differ from its immediate neighbours
     *                        and still count as the same place rather than a hole. Zero or less means
     *                        an exact match
     * @return a spot whose surroundings agree with it, or — if nothing in the radius qualifies — the
     *         nearest safe spot seen along the way regardless of agreement, which is better than
     *         refusing a request outright over uniformly rugged terrain
     */
    public Optional<Spot> nearestConsistentHeight(Spot from, int radius, int heightTolerance) {
        if (from == null) {
            return Optional.empty();
        }
        int tolerance = Math.max(0, heightTolerance);
        long deadline = System.nanoTime() + SEARCH_TIME_BUDGET_NANOS;
        // The nearest safe spot seen so far that failed the agreement check — kept rather than
        // discarded, so a radius that never agrees with itself does not have to be searched all
        // over again from scratch to answer "well, is anywhere at least safe". Also what a search
        // cut short by the time budget falls back to, for the same reason: the best answer found
        // so far beats a refusal, and it is already in hand.
        Spot bestDisagreeing = null;
        long bestDisagreeingDistance = Long.MAX_VALUE;

        for (int ring = 0; ring <= radius; ring++) {
            List<Spot> agreeing = new ArrayList<>();
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    if (System.nanoTime() >= deadline) {
                        // Whatever this ring has found so far is the honest answer to "the best
                        // this search could do in the time it was given" — better terrain further
                        // out is not worth guessing about, and it is what the terrain checked so
                        // far already ruled out as either better or unavailable.
                        return agreeing.isEmpty()
                                ? Optional.ofNullable(bestDisagreeing)
                                : agreeing.stream().min(Comparator.comparingLong(from::distanceSquaredTo));
                    }
                    Optional<Spot> found = groundInColumn(from.offset(dx, 0, dz));
                    if (found.isEmpty()) {
                        continue;
                    }
                    Spot candidate = found.get();
                    if (agreesWithNeighbours(candidate, tolerance)) {
                        agreeing.add(candidate);
                        continue;
                    }
                    long distance = from.distanceSquaredTo(candidate);
                    if (distance < bestDisagreeingDistance) {
                        bestDisagreeingDistance = distance;
                        bestDisagreeing = candidate;
                    }
                }
            }
            if (!agreeing.isEmpty()) {
                return agreeing.stream().min(Comparator.comparingLong(from::distanceSquaredTo));
            }
        }
        // Nowhere in the whole radius had ground that agreed with itself — rugged terrain
        // everywhere, most likely. Better an isolated pocket than a refusal, and it was already
        // found on the way past, so there is nothing left to search for it.
        return Optional.ofNullable(bestDisagreeing);
    }

    /**
     * The standing spot in a column, found the fast way when the world's own heightmap can answer.
     *
     * <p>{@link #safeInColumn} finds this too, eventually — but only by walking down from wherever
     * it was pointed, one {@code check()} at a time, and every one of those checks itself scans up
     * to ninety-six blocks looking for a landing while there is still nothing solid below. Pointed
     * from the sky at ordinary terrain a few hundred blocks down, that is quadratic in the distance
     * fallen and is what made a search over open air take the better part of a minute. The heightmap
     * answers "where is the ground" in one lookup with no per-block cost, so this tries that first
     * and only falls back to the walk — unchanged, exactly as safe as it always was — for whatever
     * the heightmap cannot be trusted for: overhangs, floating islands, a ceiling somebody built.
     */
    private Optional<Spot> groundInColumn(Spot column) {
        if (blocks.isLoaded(column)) {
            int surface = blocks.highestSolidY(column.x(), column.z());
            // A handful of blocks either side of the reported surface: a snow layer, a carpet, a
            // slab, or the heightmap counting a leaf or a flower rather than the ground underneath
            // it, all land within this without falling back to the expensive walk.
            Optional<Spot> viaHeightmap = standingSpotWithinWindow(column, surface + 1, 3);
            if (viaHeightmap.isPresent()) {
                return viaHeightmap;
            }
            // Missing the window is a thick canopy, an overhang or a cave mouth — the walk still has
            // to happen, but it has no business starting from wherever the column was originally
            // pointed. That is the sky for a scattered arrival, and a fallback that starts there
            // re-introduces the exact quadratic walk this whole method exists to avoid: the heightmap
            // already said where the ground roughly is, so the walk starts there instead.
            return safeInColumn(column.atHeight(surface));
        }
        return safeInColumn(column);
    }

    /**
     * Whether the ground a block either side, north and south, sits within {@code tolerance} of this
     * spot's own height.
     *
     * <h2>Why this is a narrow window and not another full column search</h2>
     * The first version of this called {@link #safeInColumn} on each neighbour — a search of the
     * <em>entire</em> world height, exactly as expensive as finding the candidate itself. Run for
     * four neighbours of every candidate in every ring, that turned a search meant to protect a
     * three-second warm-up into one that could itself take the better part of a minute. All this
     * needs to know is "is there ground within {@code tolerance} of this exact height" — so it only
     * ever looks at the {@code 2 × tolerance + 1} blocks centred on the candidate's own height,
     * which for the default tolerance of one is three blocks instead of several hundred.
     *
     * <p>An unloaded neighbour is not held against the candidate — refusing every spot beside a
     * chunk nobody has loaded would refuse most of the loaded world's edge. A loaded neighbour with
     * nothing standable in that narrow window <em>is</em> held against it: that is precisely a
     * ravine wall or a cliff edge, which is what this exists to catch.
     */
    private boolean agreesWithNeighbours(Spot candidate, int tolerance) {
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        for (int i = 0; i < dx.length; i++) {
            Spot column = candidate.offset(dx[i], 0, dz[i]);
            if (!blocks.isLoaded(column)) {
                continue;
            }
            if (standingSpotWithinWindow(column, candidate.y(), tolerance).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The standing spot in one column closest to {@code aroundY}, never looking further than
     * {@code tolerance} blocks either way.
     *
     * <p>Zero centred first, then outward a step at a time — the same "closest wins" shape as
     * {@link #safeInColumn}, just bounded to a handful of blocks instead of the whole world.
     */
    private Optional<Spot> standingSpotWithinWindow(Spot column, int aroundY, int tolerance) {
        Spot centre = column.atHeight(aroundY);
        if (isStandingSpot(centre)) {
            return Optional.of(centre);
        }
        int ceiling = blocks.highestY() - 2;
        int floor = blocks.lowestY();
        for (int step = 1; step <= tolerance; step++) {
            int up = aroundY + step;
            if (up < ceiling) {
                Spot above = column.atHeight(up);
                if (isStandingSpot(above)) {
                    return Optional.of(above);
                }
            }
            int down = aroundY - step;
            if (down > floor) {
                Spot below = column.atHeight(down);
                if (isStandingSpot(below)) {
                    return Optional.of(below);
                }
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
        Spot underfoot = spot.offset(0, -1, 0);
        if (underfoot.y() < blocks.lowestY()) {
            // See the matching guard in check(): one block past the floor is the void, not ground
            // this class simply could not identify.
            return false;
        }
        BlockKind below = blocks.at(underfoot);
        if (below == BlockKind.WATER) {
            return allowWater;
        }
        if (!below.canStandOn()) {
            return false;
        }
        return !naturalGroundOnly || blocks.isNaturalGround(underfoot);
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
