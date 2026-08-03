### src/main/java/de/raindancer/core/safety/BlockKind.java
```java
package de.raindancer.core.world.safety;

/**
 * What a block is, as far as standing in it is concerned.
 *
 * <h2>Why not just use {@code Material}</h2>
 * Because there are well over a thousand of them and this cares about six things. Reducing them once,
 * at the edge, means every rule about what is safe is arithmetic on this enum and can be tested
 * without a server — which matters, because the rules are where the bugs are and the server is where
 * they are expensive to find.
 *
 * <p>It also means a block added in a future version is {@link #UNKNOWN} rather than a crash, and
 * {@link #UNKNOWN} is treated as solid: refusing to teleport somebody into a block nobody has heard
 * of is the right way to be wrong.
 */
public enum BlockKind {

    /** Air, and everything you can stand inside — grass, torches, signs. */
    PASSABLE,

    /** Something you can stand on and cannot walk through. */
    SOLID,

    /** Water. Not fatal, but not somewhere to drop a player who was not expecting it. */
    WATER,

    /** Lava. */
    LAVA,

    /** Fire, cactus, magma, campfires, sweet berries, powder snow — it hurts to be here. */
    HARMFUL,

    /** A portal. Standing here means being sent somewhere else in a few seconds. */
    PORTAL,

    /**
     * Below the world, above it, or in a chunk nobody has loaded.
     *
     * <p>Deliberately its own answer rather than folded into "solid": a spot that cannot be checked
     * is not a spot that is safe, and the two have to be told apart to say so.
     */
    UNKNOWN;

    /** Whether a player can occupy this block without being inside something. */
    public boolean canStandIn() {
        return this == PASSABLE;
    }

    /** Whether this will hold a player up. */
    public boolean canStandOn() {
        return this == SOLID || this == UNKNOWN;
    }

    /** Whether being here costs health. */
    public boolean hurts() {
        return this == LAVA || this == HARMFUL;
    }
}

```

### src/main/java/de/raindancer/core/safety/Spot.java
```java
package de.raindancer.core.world.safety;

/**
 * A block position — where a player's feet would be.
 *
 * <p>Block coordinates rather than a {@code Location} on purpose: everything in here is arithmetic
 * on integers, and a value type with no server in it is what lets the rules be tested. The world is
 * a name for the same reason.
 */
public record Spot(String world, int x, int y, int z) {

    public Spot offset(int dx, int dy, int dz) {
        return new Spot(world, x + dx, y + dy, z + dz);
    }

    public Spot atHeight(int newY) {
        return new Spot(world, x, newY, z);
    }

    /** Squared distance, so nothing has to take a square root to compare two of these. */
    public long distanceSquaredTo(Spot other) {
        long dx = (long) x - other.x;
        long dy = (long) y - other.y;
        long dz = (long) z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** The middle of the block, which is where a player should actually be put. */
    public double centreX() {
        return x + 0.5;
    }

    public double centreZ() {
        return z + 0.5;
    }

    @Override
    public String toString() {
        return world + " " + x + ", " + y + ", " + z;
    }
}

```

### src/main/java/de/raindancer/core/safety/Blocks.java
```java
package de.raindancer.core.world.safety;

/**
 * What is where — the one thing in this package that has to ask the world.
 *
 * <p>The seam. Everything about whether a spot is safe, and about finding a better one, is
 * arithmetic on {@link BlockKind} and is tested against a grid rather than a server. This interface
 * is where that stops.
 */
public interface Blocks {

    /** What is at this position. Never null; unloaded or out of the world is {@link BlockKind#UNKNOWN}. */
    BlockKind at(Spot spot);

    /** The lowest block a world has. */
    int lowestY();

    /** One above the highest block a world has. */
    int highestY();

    /**
     * Whether this position can be looked at without loading a chunk.
     *
     * <p>Asked before scanning a wide area: on Folia, and on any busy server, generating chunks to
     * answer "is this safe" is how a teleport becomes a two-second freeze for everybody. A scan that
     * runs out of loaded ground gives up rather than pulling the world in behind it.
     */
    default boolean isLoaded(Spot spot) {
        return true;
    }
}

```

### src/main/java/de/raindancer/core/safety/Danger.java
```java
package de.raindancer.core.world.safety;

/**
 * Why a spot is not safe.
 *
 * <p>A reason rather than a boolean, because "you cannot warp there" gets asked again and "you
 * cannot warp there — it is underwater" does not. It is also the difference between a server owner
 * moving a warp and one filing a bug.
 */
public enum Danger {

    /** Nothing wrong with it. */
    NONE("it is fine"),

    /** There is a block where the player would be. */
    INSIDE_A_BLOCK("there is a block in the way"),

    /** Nothing to stand on, and a long way down. */
    NOTHING_BELOW("there is nothing to stand on"),

    /** Lava, at the feet or under them. */
    LAVA("there is lava"),

    /** Fire, cactus, magma, a campfire, sweet berries, powder snow. */
    HURTS("standing there hurts"),

    /** Underwater. Survivable, and still not where somebody wants to arrive. */
    UNDERWATER("it is underwater"),

    /** A portal, which would move the player again a moment later. */
    PORTAL("it is inside a portal"),

    /** Below the world, above it, or too far up to survive the landing. */
    OUT_OF_THE_WORLD("it is outside the world"),

    /** The chunk is not loaded, so this could not be checked at all. */
    NOT_LOADED("that part of the world is not loaded"),

    /** Far enough to fall that the landing would hurt. */
    A_LONG_WAY_DOWN("it is a long way down"),

    /** The spot itself is fine, but there is lava right beside it. */
    LAVA_NEARBY("there is lava right next to it"),

    /** The spot itself is fine, but something that hurts is right beside it. */
    HURTS_NEARBY("there is something dangerous right next to it");

    private final String saying;

    Danger(String saying) {
        this.saying = saying;
    }

    /** A phrase that finishes "you cannot go there because …", in a player's words. */
    public String saying() {
        return saying;
    }

    public boolean isSafe() {
        return this == NONE;
    }
}

```

### src/main/java/de/raindancer/core/safety/SafeSpots.java
```java
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

```

### src/main/java/de/raindancer/core/safety/BukkitBlocks.java
```java
package de.raindancer.core.world.safety;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Turning the server's thousand-odd materials into the six things standing somewhere cares about.
 *
 * <p>The seam for {@link SafeSpots}. Every rule about what is safe is on the other side of this and
 * is tested against a grid; all that is here is the reduction, and the judgement that anything this
 * does not recognise is treated as solid — refusing to put a player inside a block nobody has heard
 * of is the right way to be wrong about a block added in a future version.
 *
 * <h2>Threads</h2>
 * Reads blocks, so it must be asked on the thread that owns the region — the main thread on Paper,
 * the region's thread on Folia. It never loads a chunk: {@link #isLoaded} answers false instead, and
 * {@code ChunkHolds} is how a caller brings the ground in first.
 */
public final class BukkitBlocks implements Blocks {

    private final World world;

    public BukkitBlocks(World world) {
        this.world = world;
    }

    /** For a spot whose world is looked up by name; null when there is no such world. */
    public static BukkitBlocks of(String worldName) {
        World found = Bukkit.getWorld(worldName);
        return found == null ? null : new BukkitBlocks(found);
    }

    @Override
    public BlockKind at(Spot spot) {
        if (!isLoaded(spot) || spot.y() < world.getMinHeight() || spot.y() >= world.getMaxHeight()) {
            return BlockKind.UNKNOWN;
        }
        return kindOf(world.getBlockAt(spot.x(), spot.y(), spot.z()));
    }

    @Override
    public boolean isLoaded(Spot spot) {
        return world.isChunkLoaded(spot.x() >> 4, spot.z() >> 4);
    }

    @Override
    public int lowestY() {
        return world.getMinHeight();
    }

    @Override
    public int highestY() {
        return world.getMaxHeight();
    }

    /**
     * One block, as one of six answers.
     *
     * <p>The order matters: lava before liquids in general, harmful before passable, and the
     * catch-all last. A block that is both passable and harmful — fire, sweet berries, powder snow —
     * has to come out harmful or a player is teleported into it.
     */
    static BlockKind kindOf(Block block) {
        Material material = block.getType();
        if (material == Material.LAVA) {
            return BlockKind.LAVA;
        }
        if (material == Material.WATER || material == Material.BUBBLE_COLUMN) {
            return BlockKind.WATER;
        }
        if (isHarmful(material)) {
            return BlockKind.HARMFUL;
        }
        if (material == Material.NETHER_PORTAL || material == Material.END_PORTAL
                || material == Material.END_GATEWAY) {
            return BlockKind.PORTAL;
        }
        if (material.isAir() || block.isPassable()) {
            return BlockKind.PASSABLE;
        }
        return BlockKind.SOLID;
    }

    /** The blocks that cost health to stand in or on. */
    private static boolean isHarmful(Material material) {
        return switch (material) {
            case FIRE, SOUL_FIRE, CACTUS, MAGMA_BLOCK, SWEET_BERRY_BUSH, POWDER_SNOW,
                 CAMPFIRE, SOUL_CAMPFIRE, WITHER_ROSE, LAVA_CAULDRON, POINTED_DRIPSTONE -> true;
            default -> Tag.FIRE.isTagged(material);
        };
    }
}

```

### src/main/java/de/raindancer/core/safety/Safety.java
```java
package de.raindancer.core.world.safety;

import de.raindancer.core.world.chunk.ChunkAt;
import de.raindancer.core.world.chunk.ChunkHolds;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Is it safe to put a player there — asked the way a plugin actually needs to ask it.
 *
 * <h2>Why this sits on top of {@link SafeSpots}</h2>
 * Because of the one thing that makes the plain check useless in practice: a spot in an unloaded
 * chunk cannot be judged at all, and {@link SafeSpots} deliberately refuses to load anything, since
 * generating terrain to answer "is this warp safe" stops the server for everybody on it.
 *
 * <p>So the answer to "the chunk is not loaded" is not to shrug and it is not to load it on the main
 * thread. It is to bring the ground in first, off the main thread, and then judge — which is what
 * this does. A plugin calls {@link #findSafe} and gets a spot or an honest nothing; the loading, the
 * threading and the giving up are handled once here rather than badly in nine places.
 *
 * <h2>What it will not do</h2>
 * It never force-loads. A check is a look, and a look is not a reason to tick a chunk for the rest of
 * the server's life — that is {@link ChunkHolds#keep}, and it is a decision a plugin makes
 * deliberately with its name attached.
 *
 * <h2>Threads</h2>
 * {@link #findSafe} is asynchronous and completes on whatever thread finished the last chunk load.
 * The judging itself reads blocks, so the {@link Blocks} handed in must be usable there — the Bukkit
 * one is not, which is why callers on Paper hop back to the region's thread before using the answer.
 */
public final class Safety {

    private final ChunkHolds chunks;
    private final Function<String, Blocks> blocksIn;

    /**
     * @param blocksIn how to read a world by name; null for a world that is not loaded
     */
    public Safety(ChunkHolds chunks, Function<String, Blocks> blocksIn) {
        this.chunks = chunks;
        this.blocksIn = blocksIn;
    }

    /** The checker for one world, or empty when there is no such world loaded. */
    public Optional<SafeSpots> in(String world) {
        Blocks blocks = world == null ? null : blocksIn.apply(world);
        return blocks == null ? Optional.empty() : Optional.of(new SafeSpots(blocks));
    }

    /**
     * What is wrong with a spot, without loading anything.
     *
     * <p>{@link Danger#NOT_LOADED} here means "ask {@link #findSafe} instead", not "unsafe".
     */
    public Danger check(Spot spot) {
        return in(spot == null ? null : spot.world())
                .map(spots -> spots.check(spot))
                .orElse(Danger.OUT_OF_THE_WORLD);
    }

    /**
     * Somewhere safe near this spot, loading whatever has to be loaded to find out.
     *
     * <p>The one a teleport should call. Empty means nowhere within the radius was safe, which a
     * caller must treat as a refusal — falling back to the original spot puts the player in the
     * place already known to be dangerous, which is the bug this whole package exists to stop.
     *
     * @param radius how far sideways to look; also how much world is pulled in, so keep it modest
     */
    public CompletableFuture<Optional<Spot>> findSafe(Spot around, int radius) {
        if (around == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return chunks.forAMoment(chunksAround(around, radius))
                .thenApply(ignored -> in(around.world())
                        .flatMap(spots -> spots.nearestSafe(around, radius)));
    }

    /** The same, with the checker configured first — for water, or for looking at the surroundings. */
    public CompletableFuture<Optional<Spot>> findSafe(Spot around, int radius,
                                                      java.util.function.Consumer<SafeSpots> setUp) {
        if (around == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return chunks.forAMoment(chunksAround(around, radius))
                .thenApply(ignored -> in(around.world()).flatMap(spots -> {
                    if (setUp != null) {
                        setUp.accept(spots);
                    }
                    return spots.nearestSafe(around, radius);
                }));
    }

    /**
     * Every chunk a search of this size could touch.
     *
     * <p>Worked out from the corners rather than a chunk per block: a radius of 32 is four thousand
     * positions and at most nine chunks, and asking for the same chunk four thousand times is how a
     * safety check becomes the slow part.
     */
    private List<ChunkAt> chunksAround(Spot spot, int radius) {
        int reach = Math.max(0, radius) + 1;
        int from = (spot.x() - reach) >> 4;
        int to = (spot.x() + reach) >> 4;
        int fromZ = (spot.z() - reach) >> 4;
        int toZ = (spot.z() + reach) >> 4;

        List<ChunkAt> needed = new ArrayList<>();
        for (int x = from; x <= to; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                needed.add(new ChunkAt(spot.world(), x, z));
            }
        }
        return needed;
    }
}

```

### src/main/java/de/raindancer/core/chunk/ChunkAt.java
```java
package de.raindancer.core.world.chunk;

/**
 * One chunk, by world and chunk coordinates.
 *
 * @param world the world's name
 * @param x     chunk x — sixteen blocks wide, not one
 * @param z     chunk z
 */
public record ChunkAt(String world, int x, int z) {

    /**
     * The chunk a block is in.
     *
     * <p>An arithmetic shift rather than a division, because dividing a negative by sixteen rounds
     * towards zero: {@code -1 / 16} is 0, which puts every block west or north of spawn in the wrong
     * chunk. It is a one-line bug that only shows up on half the map, which is why it survives so
     * often.
     */
    public static ChunkAt ofBlock(String world, int blockX, int blockZ) {
        return new ChunkAt(world, blockX >> 4, blockZ >> 4);
    }

    /** The lowest block x inside this chunk. */
    public int firstBlockX() {
        return x << 4;
    }

    /** The lowest block z inside this chunk. */
    public int firstBlockZ() {
        return z << 4;
    }

    @Override
    public String toString() {
        return world + " chunk " + x + ", " + z;
    }
}

```

### src/main/java/de/raindancer/core/chunk/ChunkLoader.java
```java
package de.raindancer.core.world.chunk;

import java.util.concurrent.CompletableFuture;

/**
 * The one thing in this package that talks to the server.
 *
 * <p>The seam. Which chunks are held, by whom, and when one can actually be let go is bookkeeping
 * and is tested without a server; this is where that stops.
 */
public interface ChunkLoader {

    /** Whether a chunk is in memory right now. */
    boolean isLoaded(ChunkAt chunk);

    /**
     * Brings a chunk in, generating it if it has never existed.
     *
     * <p>Asynchronous because the alternative is not: loading a chunk on the main thread stops the
     * server for as long as the disk takes, and generating one stops it for a great deal longer.
     *
     * @return whether it is loaded; false when the world is gone or the load failed
     */
    CompletableFuture<Boolean> load(ChunkAt chunk);

    /**
     * Turns a chunk's force-load flag on or off.
     *
     * <p>This is written into the world's own data and survives a restart, which is exactly why
     * {@link ChunkHolds} exists to keep track of who asked for it.
     */
    void keepLoaded(ChunkAt chunk, boolean keep);
}

```

### src/main/java/de/raindancer/core/chunk/ChunkHolds.java
```java
package de.raindancer.core.world.chunk;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is keeping which chunks loaded, and when one can actually go.
 *
 * <h2>Why this is needed at all</h2>
 * Because a check on an unloaded chunk cannot answer. {@code SafeSpots} refuses to load anything —
 * generating terrain to answer "is this warp safe" would stop the server for everybody — so
 * something has to bring the ground in first, and something has to decide when it may go again.
 *
 * <h2>Why it counts holders instead of keeping a set</h2>
 * Two plugins can want the same chunk and only one of them be finished with it. A ghast line keeping
 * its landing pad loaded and a farm world keeping its spawn loaded may well be the same chunk, and
 * the ghast line letting go must not unload it under the farm world.
 *
 * <p>The other half is worse. A force-loaded chunk is written into the world's own data, so it
 * <em>survives a restart</em>: a plugin that forgets to let go leaves a server ticking chunks nobody
 * can account for, for ever, with nothing in any log to say why. Hence a name on every hold,
 * {@link #releaseAllFrom} when a plugin is disabled, and {@link #releaseAll} on the way out.
 *
 * <h2>The two ways to want a chunk</h2>
 * {@link #forAMoment} loads one and holds nothing — for a check that is about to happen. {@link
 * #keep} holds it until somebody says otherwise — for ground that has to stay put. Using the second
 * where the first would do is how a server ends up ticking half its map.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread.
 */
public final class ChunkHolds {

    private static final LogChannel log = Log.of("chunks");

    private final ChunkLoader loader;

    /** Who is holding what. A chunk with an empty set is removed, never left behind. */
    private final Map<ChunkAt, Set<String>> holders = new ConcurrentHashMap<>();

    public ChunkHolds(ChunkLoader loader) {
        this.loader = loader;
    }

    // ---------------------------------------------------------------------------- for a moment

    /**
     * Brings a chunk in for whatever is about to look at it, holding nothing.
     *
     * <p>What a safety check should use. The chunk stays in memory for as long as the server would
     * ordinarily keep it, which is long enough for the thing that asked, and it is not force-loaded —
     * a look is not a reason to tick a chunk for the rest of the server's life.
     *
     * @return whether it is loaded, once it is
     */
    public CompletableFuture<Boolean> forAMoment(ChunkAt chunk) {
        if (chunk == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (loader.isLoaded(chunk)) {
            return CompletableFuture.completedFuture(true);
        }
        return loader.load(chunk);
    }

    /** The same for every chunk given, answering when they are all in. */
    public CompletableFuture<Void> forAMoment(List<ChunkAt> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(chunks.stream()
                .map(this::forAMoment)
                .toArray(CompletableFuture[]::new));
    }

    // ---------------------------------------------------------------------------- for good

    /**
     * Keeps a chunk loaded until somebody lets it go.
     *
     * @param owner who wants it — a plugin's name, so a chunk that is never released has somebody's
     *              name on it rather than being a mystery in the world data
     * @return whether this changed anything; false if that owner already held it
     */
    public boolean keep(String owner, ChunkAt chunk) {
        if (chunk == null || owner == null || owner.isBlank()) {
            // Refused rather than held anonymously. A permanently loaded chunk with nobody's name on
            // it is precisely the leak this class exists to make findable.
            log.warn("A chunk was asked to be kept loaded with no owner; it was not.");
            return false;
        }
        String who = owner.trim();
        boolean[] first = {false};
        holders.compute(chunk, (at, current) -> {
            Set<String> set = current == null ? new LinkedHashSet<>() : current;
            first[0] = set.add(who) && set.size() == 1;
            return set;
        });
        if (first[0]) {
            loader.keepLoaded(chunk, true);
            log.info("{} is keeping {} loaded.", who, chunk);
            return true;
        }
        return false;
    }

    /**
     * Lets one owner's hold go.
     *
     * @return whether the chunk was actually released — false when somebody else still wants it, or
     *         when this owner was not holding it
     */
    public boolean release(String owner, ChunkAt chunk) {
        if (chunk == null || owner == null || owner.isBlank()) {
            return false;
        }
        String who = owner.trim();
        boolean[] last = {false};
        holders.computeIfPresent(chunk, (at, set) -> {
            if (!set.remove(who)) {
                return set;
            }
            last[0] = set.isEmpty();
            return set.isEmpty() ? null : set;
        });
        if (last[0]) {
            loader.keepLoaded(chunk, false);
            return true;
        }
        return false;
    }

    /**
     * Lets go of everything one plugin held — for a plugin being disabled.
     *
     * @return how many chunks were actually released
     */
    public int releaseAllFrom(String owner) {
        if (owner == null || owner.isBlank()) {
            return 0;
        }
        String who = owner.trim();
        int released = 0;
        for (ChunkAt chunk : List.copyOf(holders.keySet())) {
            if (release(who, chunk)) {
                released++;
            }
        }
        return released;
    }

    /**
     * Lets go of everything — for a shutdown.
     *
     * <p>Called on the way out whatever else happened, because the flag outlives the process.
     *
     * @return how many chunks were released
     */
    public int releaseAll() {
        int released = 0;
        for (ChunkAt chunk : List.copyOf(holders.keySet())) {
            holders.remove(chunk);
            loader.keepLoaded(chunk, false);
            released++;
        }
        return released;
    }

    // ---------------------------------------------------------------------------- looking

    /** Whether anybody is holding this chunk. */
    public boolean isHeld(ChunkAt chunk) {
        return chunk != null && holders.containsKey(chunk);
    }

    /** Who is holding it — so a chunk that will not go away has names attached. */
    public Set<String> holdersOf(ChunkAt chunk) {
        Set<String> set = chunk == null ? null : holders.get(chunk);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    /** Everything one plugin is holding. */
    public Set<ChunkAt> heldBy(String owner) {
        if (owner == null || owner.isBlank()) {
            return Set.of();
        }
        String who = owner.trim();
        return holders.entrySet().stream()
                .filter(entry -> entry.getValue().contains(who))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Every chunk being held, by anybody. */
    public Set<ChunkAt> all() {
        return Collections.unmodifiableSet(Set.copyOf(holders.keySet()));
    }

    public int size() {
        return holders.size();
    }
}

```

### src/main/java/de/raindancer/core/chunk/BukkitChunkLoader.java
```java
package de.raindancer.core.world.chunk;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * The handful of lines that actually load a chunk.
 *
 * <p>Everything about who wants what and when it may go lives in {@link ChunkHolds} and is tested
 * without a server. This is the seam.
 */
public final class BukkitChunkLoader implements ChunkLoader {

    private static final LogChannel log = Log.of("chunks");

    @Override
    public boolean isLoaded(ChunkAt chunk) {
        World world = Bukkit.getWorld(chunk.world());
        return world != null && world.isChunkLoaded(chunk.x(), chunk.z());
    }

    @Override
    public CompletableFuture<Boolean> load(ChunkAt chunk) {
        World world = Bukkit.getWorld(chunk.world());
        if (world == null) {
            // A world that is not loaded is not an error worth a stack trace: it is a warp somebody
            // set in a world that has since been removed, which the caller has to handle anyway.
            return CompletableFuture.completedFuture(false);
        }
        // getChunkAtAsync rather than getChunkAt: loading on the main thread stops the server for as
        // long as the disk takes, and generating stops it for a great deal longer. This is also what
        // makes the call safe under Folia, where the chunk belongs to a region and not to a thread.
        return world.getChunkAtAsync(chunk.x(), chunk.z(), true)
                .thenApply(loaded -> loaded != null)
                .exceptionally(failure -> {
                    log.warn("Could not load {} ({})", chunk, failure.getMessage());
                    return false;
                });
    }

    @Override
    public void keepLoaded(ChunkAt chunk, boolean keep) {
        World world = Bukkit.getWorld(chunk.world());
        if (world != null) {
            world.setChunkForceLoaded(chunk.x(), chunk.z(), keep);
        }
    }
}

```

### src/main/java/de/raindancer/core/effect/SoundCue.java
```java
package de.raindancer.core.ui.effect;

/**
 * One sound, as the protocol wants it.
 *
 * <p>A key rather than Bukkit's {@code Sound} enum, so a resource pack's own sound works exactly like
 * a vanilla one and a name that a future version renames is a warning rather than a compile error.
 *
 * @param key    the sound's name — {@code block.note_block.bell}, or one from a resource pack
 * @param volume how loud, and past 1.0 also how far away it can be heard
 * @param pitch  0.5 to 2.0; anything else is silently ignored by the client
 */
public record SoundCue(String key, float volume, float pitch) {

    public SoundCue {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a sound needs a name");
        }
        key = key.trim();
        // Clamped rather than rejected: an out-of-range pitch is not refused by the client, it is
        // ignored, which reads as "the sound is broken" and sends somebody hunting in the wrong place.
        volume = Math.clamp(volume, 0f, 10f);
        pitch = Math.clamp(pitch, 0.5f, 2.0f);
    }

    /** The same sound, at a different pitch — for the two-note up and down every menu wants. */
    public SoundCue atPitch(float pitch) {
        return new SoundCue(key, volume, pitch);
    }

    public boolean isSilent() {
        return volume <= 0f;
    }
}

```

### src/main/java/de/raindancer/core/effect/ParticleCue.java
```java
package de.raindancer.core.ui.effect;

/**
 * A puff of particles.
 *
 * <p>The particle is named rather than typed for the same reason a sound is: a name that a future
 * version drops is a warning at runtime rather than a plugin that will not compile against the new
 * server.
 *
 * @param particle the particle's name, as the server spells it — {@code HAPPY_VILLAGER}
 * @param count    how many
 * @param spreadX  how far they scatter, in blocks
 * @param spreadY  the same, vertically
 * @param spreadZ  the same again
 * @param speed    how fast they move; for several particles this is their "extra" value instead
 */
public record ParticleCue(String particle, int count, double spreadX, double spreadY,
                          double spreadZ, double speed) {

    public ParticleCue {
        if (particle == null || particle.isBlank()) {
            throw new IllegalArgumentException("a particle effect needs a particle");
        }
        particle = particle.trim().toUpperCase(java.util.Locale.ROOT);
        // Capped rather than trusted. A thousand particles is a plugin's typo and a client's
        // stutter, and the player it happens to has no way of telling which plugin did it.
        count = Math.clamp(count, 0, 500);
        speed = Math.max(0, speed);
    }

    /** A simple burst at one spot. */
    public static ParticleCue of(String particle, int count) {
        return new ParticleCue(particle, count, 0, 0, 0, 0);
    }

    public boolean isNothing() {
        return count <= 0;
    }
}

```

### src/main/java/de/raindancer/core/effect/Effect.java
```java
package de.raindancer.core.ui.effect;

/**
 * What one named cue actually does: a sound, some particles, or both.
 *
 * <p>Either half may be absent, and both being absent is a deliberate silence rather than a mistake
 * — see {@link #silence()}, which is how a server owner turns off a cue that every plugin on the
 * server is asking for.
 *
 * @param sound     the sound, or null
 * @param particles the particles, or null
 */
public record Effect(SoundCue sound, ParticleCue particles) {

    /** Just a sound. */
    public static Effect of(SoundCue sound) {
        return new Effect(sound, null);
    }

    /** Just particles. */
    public static Effect of(ParticleCue particles) {
        return new Effect(null, particles);
    }

    /**
     * Nothing at all.
     *
     * <p>Bound over a cue to switch it off everywhere at once. Better than removing it: a cue that
     * is missing is a warning in the log every time a plugin asks for it, and a cue that is silent
     * is a decision.
     */
    public static Effect silence() {
        return new Effect(null, null);
    }

    public boolean isSilent() {
        return (sound == null || sound.isSilent()) && (particles == null || particles.isNothing());
    }
}

```

### src/main/java/de/raindancer/core/effect/EffectSink.java
```java
package de.raindancer.core.ui.effect;

import java.util.UUID;

/**
 * The one thing in this package that talks to the server.
 *
 * <p>Everything else — what a cue means, whether it has been played too recently, whether it is
 * switched off — is bookkeeping and is tested without a server.
 */
public interface EffectSink {

    /** A sound only this player hears, from where they are. */
    void toPlayer(UUID player, SoundCue sound);

    /** Particles only this player sees, where they are. */
    void toPlayer(UUID player, ParticleCue particles);

    /** A sound at a place, for everybody near enough. */
    void atPlace(String world, double x, double y, double z, SoundCue sound);

    /** Particles at a place, for everybody who can see it. */
    void atPlace(String world, double x, double y, double z, ParticleCue particles);

    /**
     * Stops one sound a player is hearing.
     *
     * <p>Only sounds. Particles are drawn and gone — there is nothing to stop — and a call that
     * pretended otherwise would be one that silently does nothing.
     */
    void stopForPlayer(UUID player, String soundKey);

    /** Stops everything a player is hearing from the server. */
    void stopAllForPlayer(UUID player);
}

```

### src/main/java/de/raindancer/core/effect/Cues.java
```java
package de.raindancer.core.ui.effect;

import java.util.List;

/**
 * The cues every plugin needs, named by what they mean rather than by what they sound like.
 *
 * <h2>Why names and not sounds</h2>
 * Because {@code play(player, Cues.NO)} still makes sense after somebody decides the refusal should
 * be a bass note rather than a villager, and {@code playSound(player, ENTITY_VILLAGER_NO)} does not.
 * Asking by meaning is what lets one line in one place change how every menu in every plugin sounds.
 *
 * <h2>Why there are this many</h2>
 * Because a handful would not be enough to stop anybody. A plugin that cannot find a cue for what it
 * is doing writes its own {@code playSound} and the whole point is lost, so the list has to cover
 * what plugins actually do: refuse things, open menus, teleport people, hurt them, heal them, hand
 * them things, count down at them. Each is bound to a vanilla sound and, where it is something you
 * ought to see as well as hear, to particles too.
 *
 * <p>They are plain strings so a plugin can add its own — {@code "ghastlines:whoosh"} — without
 * anything here knowing about it, and so a server owner can rebind any of them the same way.
 */
public final class Cues {

    private Cues() {
    }

    // ---------------------------------------------------------------- answers

    /** Something worked. */
    public static final String OK = "core:ok";

    /** Something was refused. The most important one to get right: players hear it most. */
    public static final String NO = "core:no";

    /** Something is worth noticing but is not a refusal. */
    public static final String WARN = "core:warn";

    /** Something went wrong on the server's side rather than the player's. */
    public static final String ERROR = "core:error";

    /** Somebody is being spoken to — a message that should not be scrolled past. */
    public static final String NOTIFY = "core:notify";

    // ---------------------------------------------------------------- menus

    /** A button in a menu was pressed. */
    public static final String CLICK = "core:click";

    /** A menu opened. */
    public static final String OPEN = "core:open";

    /** A menu closed. */
    public static final String CLOSE = "core:close";

    /** A page turned, or a list scrolled. */
    public static final String PAGE = "core:page";

    // ---------------------------------------------------------------- moving about

    /** Somebody arrived somewhere. */
    public static final String TELEPORT = "core:teleport";

    /** Something is being counted down — one tick of it. */
    public static final String COUNTDOWN = "core:countdown";

    /** The countdown reached zero. */
    public static final String COUNTDOWN_DONE = "core:countdown-done";

    /** Somebody entered a place that has an owner — a claim, a town, a zone. */
    public static final String ENTER = "core:enter";

    /** And left it again. */
    public static final String LEAVE = "core:leave";

    // ---------------------------------------------------------------- things happening to you

    /** Something was earned — an achievement, a level, a rank. */
    public static final String EARNED = "core:earned";

    /** Something was given — an item, a reward, money. */
    public static final String REWARD = "core:reward";

    /** Somebody was healed or fed. */
    public static final String HEAL = "core:heal";

    /** Somebody was hurt by a plugin rather than by the world. */
    public static final String HURT = "core:hurt";

    /** Something was created out of nothing — a spawned mob, a conjured block. */
    public static final String SUMMON = "core:summon";

    /** Something was taken away — a despawn, a cleared drop, a removed entity. */
    public static final String VANISH = "core:vanish";

    /** Something magical happened that does not fit anywhere else. */
    public static final String MAGIC = "core:magic";

    /** A custom item's ability went off. */
    public static final String ABILITY = "core:ability";

    /** An ability was used and is now on cooldown, or was used too soon. */
    public static final String COOLDOWN = "core:cooldown";

    /** Every cue this ships with, for a menu, a settings page or a test. */
    public static List<String> all() {
        return List.of(OK, NO, WARN, ERROR, NOTIFY,
                CLICK, OPEN, CLOSE, PAGE,
                TELEPORT, COUNTDOWN, COUNTDOWN_DONE, ENTER, LEAVE,
                EARNED, REWARD, HEAL, HURT, SUMMON, VANISH, MAGIC, ABILITY, COOLDOWN);
    }
}

```

### src/main/java/de/raindancer/core/effect/Effects.java
```java
package de.raindancer.core.ui.effect;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Every sound and every particle any plugin makes.
 *
 * <h2>Why this is Core's, and not a wrapper</h2>
 * Two reasons. The first is that nine plugins each choosing their own click sound gives a server nine
 * different clicks, and an owner who wants them quieter has to edit nine plugins — if they can find
 * them, which they cannot, because a sound is one line buried in a menu handler. Asking by
 * <em>meaning</em> ({@link Cues#NO}) rather than by sound, and binding that meaning in one place, is
 * the difference between a server that sounds like itself and one that sounds like a plugin folder.
 *
 * <p>The second is the same collision as the action bar and the sidebar. A plugin playing a cue on
 * every tick of something is a plugin deafening a player, and it never finds out, because from inside
 * that plugin it is one sound. So the same cue to the same player twice in a moment is played once.
 *
 * <h2>Using it</h2>
 * <pre>{@code
 * core.effects().play(player.getUniqueId(), Cues.NO);
 * core.effects().playAt(world, x, y, z, Cues.TELEPORT);
 *
 * // a plugin's own, which anybody may then rebind
 * core.effects().define("ghastlines:whoosh", Effect.of(new SoundCue("entity.ghast.shoot", .8f, 1.2f)));
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. Whether the sink is depends on the sink; the Bukkit one schedules itself onto
 * the right thread for the region.
 */
public final class Effects {

    private static final LogChannel log = Log.of("effects");

    /**
     * How close together the same cue has to be to count as a repeat.
     *
     * <p>Long enough to swallow a per-tick loop, short enough that a player clicking quickly through
     * a menu still hears every click.
     */
    private static final Duration DEFAULT_GAP = Duration.ofMillis(120);

    private final EffectSink sink;
    private final LongSupplier clock;

    private final Map<String, Effect> bound = new ConcurrentHashMap<>();
    /** When each player last heard each cue. Keyed by both, so cues do not suppress each other. */
    private final Map<String, Long> lastPlayed = new ConcurrentHashMap<>();
    /** Cues somebody asked for that nobody had defined. Said once each, not once per call. */
    private final Set<String> missing = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled = true;
    private volatile long gapMillis = DEFAULT_GAP.toMillis();

    /** @param clock milliseconds; injected so the repeat window can be tested without waiting */
    public Effects(EffectSink sink, LongSupplier clock) {
        this.sink = sink;
        this.clock = clock;
        defineDefaults();
    }

    /**
     * What Core ships with.
     *
     * <p>Vanilla sounds on purpose: a default that needs a resource pack is a default that is silent
     * on most servers, and silence is indistinguishable from something being broken.
     */
    private void defineDefaults() {
        // Answers
        bound.put(Cues.OK, Effect.of(new SoundCue("entity.experience_orb.pickup", 0.6f, 1.6f)));
        bound.put(Cues.NO, Effect.of(new SoundCue("block.note_block.bass", 0.7f, 0.7f)));
        bound.put(Cues.WARN, Effect.of(new SoundCue("block.note_block.pling", 0.6f, 0.8f)));
        bound.put(Cues.ERROR, Effect.of(new SoundCue("entity.item.break", 0.7f, 0.8f)));
        bound.put(Cues.NOTIFY, Effect.of(new SoundCue("block.note_block.chime", 0.6f, 1.5f)));

        // Menus. Quiet on purpose: these are heard hundreds of times an hour.
        bound.put(Cues.CLICK, Effect.of(new SoundCue("ui.button.click", 0.35f, 1.0f)));
        bound.put(Cues.OPEN, Effect.of(new SoundCue("block.barrel.open", 0.4f, 1.4f)));
        bound.put(Cues.CLOSE, Effect.of(new SoundCue("block.barrel.close", 0.4f, 1.4f)));
        bound.put(Cues.PAGE, Effect.of(new SoundCue("item.book.page_turn", 0.5f, 1.1f)));

        // Moving about
        bound.put(Cues.TELEPORT, new Effect(new SoundCue("entity.enderman.teleport", 0.6f, 1.2f),
                new ParticleCue("PORTAL", 24, 0.4, 0.6, 0.4, 0.06)));
        bound.put(Cues.COUNTDOWN, Effect.of(new SoundCue("block.note_block.hat", 0.5f, 1.2f)));
        bound.put(Cues.COUNTDOWN_DONE,
                Effect.of(new SoundCue("block.note_block.bell", 0.7f, 1.6f)));
        bound.put(Cues.ENTER, Effect.of(new SoundCue("block.note_block.harp", 0.4f, 1.5f)));
        bound.put(Cues.LEAVE, Effect.of(new SoundCue("block.note_block.harp", 0.4f, 1.0f)));

        // Things happening to you
        bound.put(Cues.EARNED, new Effect(new SoundCue("entity.player.levelup", 0.7f, 1.4f),
                new ParticleCue("HAPPY_VILLAGER", 12, 0.4, 0.5, 0.4, 0.02)));
        bound.put(Cues.REWARD, Effect.of(new SoundCue("entity.item.pickup", 0.7f, 1.2f)));
        bound.put(Cues.HEAL, new Effect(new SoundCue("entity.player.burp", 0.4f, 1.6f),
                new ParticleCue("HEART", 6, 0.4, 0.5, 0.4, 0.01)));
        bound.put(Cues.HURT, Effect.of(new SoundCue("entity.player.hurt", 0.6f, 1.0f)));
        bound.put(Cues.SUMMON, new Effect(new SoundCue("entity.illusioner_cast_spell", 0.6f, 1.2f),
                new ParticleCue("CLOUD", 20, 0.4, 0.3, 0.4, 0.03)));
        bound.put(Cues.VANISH, new Effect(new SoundCue("entity.generic_extinguish_fire", 0.5f, 1.4f),
                new ParticleCue("SMOKE", 16, 0.3, 0.3, 0.3, 0.02)));
        bound.put(Cues.MAGIC, new Effect(new SoundCue("block.enchantment_table.use", 0.6f, 1.2f),
                new ParticleCue("ENCHANT", 30, 0.5, 0.8, 0.5, 0.5)));
        bound.put(Cues.ABILITY, Effect.of(new SoundCue("entity.evoker.cast_spell", 0.6f, 1.3f)));
        bound.put(Cues.COOLDOWN, Effect.of(new SoundCue("block.dispenser.fail", 0.6f, 1.0f)));
    }

    // ---------------------------------------------------------------------------- the vocabulary

    /**
     * Binds a name to what it does, replacing whatever it was.
     *
     * <p>How a plugin adds its own, and how a server owner changes one that already exists. Both are
     * the same call on purpose: there is nothing special about Core's own cues.
     */
    public void define(String cue, Effect effect) {
        if (cue == null || cue.isBlank() || effect == null) {
            return;
        }
        bound.put(cue.trim(), effect);
        missing.remove(cue.trim());
    }

    /** Forgets a cue entirely. Prefer binding {@link Effect#silence()} — see there for why. */
    public void undefine(String cue) {
        if (cue != null) {
            bound.remove(cue.trim());
        }
    }

    public boolean isDefined(String cue) {
        return cue != null && bound.containsKey(cue.trim());
    }

    /** What a cue is currently bound to. */
    public Optional<Effect> boundTo(String cue) {
        return cue == null ? Optional.empty() : Optional.ofNullable(bound.get(cue.trim()));
    }

    /** Every cue anybody has defined, in the order they were defined. */
    public Map<String, Effect> all() {
        return new LinkedHashMap<>(bound);
    }

    /** Cues somebody asked for that nobody had defined — usually a typo, always worth knowing. */
    public List<String> problems() {
        return missing.stream().map(cue -> "nothing is bound to '" + cue + "'").sorted().toList();
    }

    // ---------------------------------------------------------------------------- settings

    /** Whether anything is played at all. */
    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** How close together the same cue counts as a repeat. Zero switches the suppression off. */
    public void minimumGap(Duration gap) {
        this.gapMillis = gap == null || gap.isNegative() ? 0 : gap.toMillis();
    }

    // ---------------------------------------------------------------------------- playing

    /** Plays a cue for one player, where they are. */
    public void play(UUID player, String cue) {
        if (player == null || !enabled) {
            return;
        }
        Effect effect = lookUp(cue);
        if (effect == null || effect.isSilent() || tooSoon(player, cue)) {
            return;
        }
        if (effect.sound() != null) {
            sink.toPlayer(player, effect.sound());
        }
        if (effect.particles() != null && !effect.particles().isNothing()) {
            sink.toPlayer(player, effect.particles());
        }
    }

    /**
     * Plays a cue at a place, for everybody near enough.
     *
     * <p>Not throttled against a player's own cues: somebody else teleporting nearby is a different
     * event from your own teleport, and folding them together would swallow one of them.
     */
    public void playAt(String world, double x, double y, double z, String cue) {
        if (world == null || !enabled) {
            return;
        }
        Effect effect = lookUp(cue);
        if (effect == null || effect.isSilent()) {
            return;
        }
        if (effect.sound() != null) {
            sink.atPlace(world, x, y, z, effect.sound());
        }
        if (effect.particles() != null && !effect.particles().isNothing()) {
            sink.atPlace(world, x, y, z, effect.particles());
        }
    }

    /** The same for several players at once — one refusal heard by a whole party. */
    public void playForAll(Iterable<UUID> players, String cue) {
        if (players == null) {
            return;
        }
        players.forEach(player -> play(player, cue));
    }

    // ---------------------------------------------------------------------------- stopping

    /**
     * Stops a cue this player is hearing.
     *
     * <p>Needed as soon as anything lasts longer than an instant — a jukebox, a countdown drone, a
     * boss theme. Without it the only way to end one is to wait, and a player who reconnects mid-cue
     * hears it again on top of itself.
     *
     * <p>Sounds only. Particles are drawn and gone; there is nothing to stop, and this deliberately
     * does not pretend there is.
     */
    public void stop(UUID player, String cue) {
        if (player == null) {
            return;
        }
        Effect effect = lookUp(cue);
        if (effect == null || effect.sound() == null || effect.sound().isSilent()) {
            return;
        }
        sink.stopForPlayer(player, effect.sound().key());
        // Forgotten rather than left behind, so a plugin that stops a cue and starts it again is
        // not silently refused by the repeat window it just filled.
        lastPlayed.remove(player + "/" + cue.trim());
    }

    /** Stops everything this player is hearing from the server. */
    public void stopAll(UUID player) {
        if (player != null) {
            sink.stopAllForPlayer(player);
            forget(player);
        }
    }

    // ---------------------------------------------------------------------------- bookkeeping

    /**
     * Forgets a player — for one who has left.
     *
     * <p>Without it there is one entry per player per cue kept for ever, which is a leak that grows
     * with every player who has ever joined.
     */
    public void forget(UUID player) {
        if (player == null) {
            return;
        }
        String prefix = player + "/";
        lastPlayed.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** How much is being remembered, for a test and for a health check. */
    public int remembering() {
        return lastPlayed.size();
    }

    private Effect lookUp(String cue) {
        if (cue == null || cue.isBlank()) {
            return null;
        }
        String name = cue.trim();
        Effect effect = bound.get(name);
        if (effect == null && missing.add(name)) {
            // Once, not once per call: a cue asked for on every tick would otherwise fill the log
            // faster than the thing it is complaining about.
            log.warn("Nothing is bound to the effect '{}'; nothing was played.", name);
        }
        return effect;
    }

    /**
     * Whether this player heard this cue a moment ago.
     *
     * <p>Records the time as it answers, so two threads asking at once cannot both be told no.
     */
    private boolean tooSoon(UUID player, String cue) {
        long gap = gapMillis;
        if (gap <= 0) {
            return false;
        }
        String key = player + "/" + cue.trim();
        long now = clock.getAsLong();
        Long previous = lastPlayed.put(key, now);
        return previous != null && now - previous < gap;
    }

    /** Everything currently bound, as lines for a banner or a menu. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        bound.forEach((cue, effect) -> lines.add(cue + " → "
                + (effect.sound() == null ? "no sound" : effect.sound().key())
                + (effect.particles() == null ? "" : " + " + effect.particles().particle())));
        lines.sort(String::compareTo);
        return lines;
    }
}

```

### src/main/java/de/raindancer/core/effect/BukkitEffectSink.java
```java
package de.raindancer.core.ui.effect;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lines that actually make a noise.
 *
 * <p>Everything worth getting right — what a cue means, whether it has just been played, whether it
 * is switched off — is on the other side of {@link EffectSink} and is tested without a server.
 *
 * <p>The one judgement here is what to do with a name the server does not know. A sound key is
 * passed through as a key, so a resource pack's own sound works exactly like a vanilla one and a
 * misspelled one is simply silent — that is the game's behaviour and it is the right one. A particle
 * has to be a real enum constant, so an unknown one is dropped and said once: a name that a future
 * version renames should be a line in the log, not a crash in whatever was happening.
 */
public final class BukkitEffectSink implements EffectSink {

    private static final LogChannel log = Log.of("effects");

    /** Particle names that turned out not to exist. Complained about once each. */
    private final Set<String> unknown = ConcurrentHashMap.newKeySet();

    @Override
    public void toPlayer(UUID player, SoundCue sound) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            // By key rather than by Sound: a resource pack's own sound is then no different from a
            // vanilla one, which is the whole point of contributing packs in the first place.
            online.playSound(online.getLocation(), sound.key(), sound.volume(), sound.pitch());
        }
    }

    @Override
    public void toPlayer(UUID player, ParticleCue particles) {
        Player online = Bukkit.getPlayer(player);
        Particle particle = particleOf(particles.particle());
        if (online != null && particle != null) {
            online.spawnParticle(particle, online.getLocation().add(0, 1, 0), particles.count(),
                    particles.spreadX(), particles.spreadY(), particles.spreadZ(),
                    particles.speed());
        }
    }

    @Override
    public void atPlace(String world, double x, double y, double z, SoundCue sound) {
        World found = Bukkit.getWorld(world);
        if (found != null) {
            found.playSound(new Location(found, x, y, z), sound.key(), sound.volume(),
                    sound.pitch());
        }
    }

    @Override
    public void atPlace(String world, double x, double y, double z, ParticleCue particles) {
        World found = Bukkit.getWorld(world);
        Particle particle = particleOf(particles.particle());
        if (found != null && particle != null) {
            found.spawnParticle(particle, new Location(found, x, y, z), particles.count(),
                    particles.spreadX(), particles.spreadY(), particles.spreadZ(),
                    particles.speed());
        }
    }

    @Override
    public void stopForPlayer(UUID player, String soundKey) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            online.stopSound(soundKey);
        }
    }

    @Override
    public void stopAllForPlayer(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            online.stopAllSounds();
        }
    }

    /** A particle by name, or null once, loudly. */
    private Particle particleOf(String name) {
        try {
            return Particle.valueOf(name);
        } catch (IllegalArgumentException notAParticle) {
            if (unknown.add(name)) {
                log.warn("This server has no particle called '{}'; that part of the effect was "
                        + "skipped.", name);
            }
            return null;
        }
    }

    /** Whether a sound name is one the server itself knows — for a chooser, not for playing. */
    public static boolean isVanillaSound(String key) {
        return Sound.class.isEnum() && org.bukkit.Registry.SOUNDS.get(
                org.bukkit.NamespacedKey.minecraft(key.replace("minecraft:", ""))) != null;
    }
}

```

### src/main/java/de/raindancer/core/time/Times.java
```java
package de.raindancer.core.world.time;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading and writing the lengths of time people actually type.
 *
 * <h2>Why not {@link Duration#parse}</h2>
 * Because it wants {@code PT30M}, and nobody has ever typed that into a chat box. People type
 * {@code 2min}, {@code 2m}, {@code 90s}, {@code 1h30m}, {@code 2 weeks}, {@code perm} — and every
 * plugin that takes a length of time grew its own half-parser understanding three of those and
 * silently misreading the rest.
 *
 * <h2>The ambiguity, on purpose</h2>
 * {@code m} is <b>minutes</b>. Everybody who has ever typed {@code /mute someone 5m} meant five
 * minutes, and a parser that read it as months would be catastrophically, silently wrong. Months are
 * {@code mo}, {@code month}, {@code months}, or a <b>capital {@code M}</b> — the shorthand people
 * already know from cron and from every ban plugin. That one letter is the whole reason this class
 * is worth having rather than a regex in each plugin.
 *
 * <h2>Months and years are not fixed lengths</h2>
 * {@link #parse} has to answer with a {@link Duration}, so a month there is thirty days and a year is
 * three hundred and sixty-five: an approximation, which {@link #isApproximate} will admit to. When
 * the exact answer matters — a one-month ban should end on the same day next month, not "in thirty
 * days" — use {@link #after}, which does real calendar arithmetic.
 *
 * <h2>It would rather refuse than guess</h2>
 * Anything not wholly understood comes back empty. Reading {@code 1h} out of {@code "1h and a bit"}
 * and dropping the rest is how somebody ends up banned for a length nobody asked for.
 */
public final class Times {

    /**
     * A number and a unit.
     *
     * <p>Matched case-insensitively so "2 Minutes" and "2H" work, with the longest spellings first
     * so "Minutes" is not read as a bare "M" with "inutes" left over. Which of {@code m} and
     * {@code M} was actually typed is then decided in {@link #unitOf} on the captured text, because
     * that single letter is the difference between two minutes and two months.
     */
    private static final Pattern PART = Pattern.compile(
            "(\\d+)\\s*(mo(?:nths?|ns?)?|minutes?|mins?|seconds?|secs?|hours?|hrs?|days?"
                    + "|weeks?|wks?|years?|yrs?|M|m|s|h|d|w|y)?",
            Pattern.CASE_INSENSITIVE);

    /** What somebody types when they mean it never ends. */
    private static final List<String> FOR_EVER = List.of("perm", "permanent", "permanently",
            "forever", "for ever", "never", "inf", "infinite", "always");

    /** A month, when one has to be a number of days. Wrong by up to a day and a half, and says so. */
    private static final long DAYS_IN_A_MONTH = 30;

    private static final long DAYS_IN_A_YEAR = 365;

    /** Longer than any server will run. Past this, refuse rather than overflow. */
    private static final Duration TOO_LONG = Duration.ofDays(365L * 1000);

    private Times() {
    }

    // ---------------------------------------------------------------------------- reading

    /**
     * How long somebody meant.
     *
     * <p>Empty means it could not be read <em>or</em> that they said "for ever" — the two are told
     * apart with {@link #isForEver}, which a caller must ask first. Folding them together is how a
     * typo becomes a permanent ban.
     */
    public static Optional<Duration> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String cleaned = text.trim();
        if (isForEver(cleaned)) {
            return Optional.empty();
        }
        // Lowercased everywhere except the unit letters, so "2 Minutes" works while M stays distinct
        // from m. Doing it the other way round is what makes most parsers of this get months wrong.
        String normalised = cleaned.replace(',', ' ').replaceAll("\\s+", " ");

        Matcher matcher = PART.matcher(normalised);
        Duration total = Duration.ZERO;
        int consumedTo = 0;
        boolean any = false;

        while (matcher.find()) {
            if (matcher.start() != skipSpaces(normalised, consumedTo)) {
                // Something that is not a number-and-unit sits between the parts. Refused, rather
                // than quietly reading the bits that happen to look like a length.
                return Optional.empty();
            }
            Optional<Duration> part = partOf(matcher.group(1), matcher.group(2));
            if (part.isEmpty()) {
                return Optional.empty();
            }
            total = total.plus(part.get());
            if (total.compareTo(TOO_LONG) > 0) {
                return Optional.empty();
            }
            consumedTo = matcher.end();
            any = true;
        }

        if (!any || skipSpaces(normalised, consumedTo) != normalised.length()) {
            return Optional.empty();
        }
        return total.isZero() || total.isNegative() ? Optional.empty() : Optional.of(total);
    }

    /** One number and one unit. A missing unit is seconds, which is what a bare number means. */
    private static Optional<Duration> partOf(String number, String unit) {
        long amount;
        try {
            amount = Long.parseLong(number);
        } catch (NumberFormatException tooBig) {
            // A number too long for a long is not a length of time anybody meant.
            return Optional.empty();
        }
        if (amount < 0) {
            return Optional.empty();
        }
        String kind = unit == null ? "s" : unit;
        try {
            return Optional.of(switch (unitOf(kind)) {
                case SECONDS -> Duration.ofSeconds(amount);
                case MINUTES -> Duration.ofMinutes(amount);
                case HOURS -> Duration.ofHours(amount);
                case DAYS -> Duration.ofDays(amount);
                case WEEKS -> Duration.ofDays(Math.multiplyExact(amount, 7));
                case MONTHS -> Duration.ofDays(Math.multiplyExact(amount, DAYS_IN_A_MONTH));
                case YEARS -> Duration.ofDays(Math.multiplyExact(amount, DAYS_IN_A_YEAR));
            });
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
    }

    /** The units this understands. */
    private enum Unit { SECONDS, MINUTES, HOURS, DAYS, WEEKS, MONTHS, YEARS }

    /**
     * Which unit a suffix is.
     *
     * <p>The capital {@code M} is checked before anything is lowercased — it is the one place in
     * this class where case carries meaning, and losing it turns minutes into months.
     */
    private static Unit unitOf(String suffix) {
        if (suffix.equals("M")) {
            return Unit.MONTHS;
        }
        String lower = suffix.toLowerCase(Locale.ROOT);
        if (lower.startsWith("mo")) {
            return Unit.MONTHS;
        }
        return switch (lower) {
            case "m", "min", "mins", "minute", "minutes" -> Unit.MINUTES;
            case "h", "hr", "hrs", "hour", "hours" -> Unit.HOURS;
            case "d", "day", "days" -> Unit.DAYS;
            case "w", "wk", "wks", "week", "weeks" -> Unit.WEEKS;
            case "y", "yr", "yrs", "year", "years" -> Unit.YEARS;
            default -> Unit.SECONDS;
        };
    }

    private static int skipSpaces(String text, int from) {
        int at = from;
        while (at < text.length() && text.charAt(at) == ' ') {
            at++;
        }
        return at;
    }

    /** Whether somebody meant it to never end. */
    public static boolean isForEver(String text) {
        return text != null && FOR_EVER.contains(text.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether reading this as a {@link Duration} involved rounding a month or a year.
     *
     * <p>For a caller who needs the exact answer to know it has to use {@link #after} instead.
     */
    public static boolean isApproximate(String text) {
        if (text == null) {
            return false;
        }
        Matcher matcher = PART.matcher(text.trim());
        while (matcher.find()) {
            String unit = matcher.group(2);
            if (unit != null) {
                Unit kind = unitOf(unit);
                if (kind == Unit.MONTHS || kind == Unit.YEARS) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------- real dates

    /**
     * When something typed as a length of time would actually end.
     *
     * <p>The exact version. A month here is a calendar month, so a ban set on the 15th ends on the
     * 15th and one set on the 31st of January ends on the 28th of February rather than spilling into
     * March. That is what somebody means by "a month", and thirty days is not it.
     *
     * @return when it ends, or empty when the text could not be read or means for ever
     */
    public static Optional<Instant> after(Instant from, String text) {
        if (from == null || text == null || text.isBlank() || isForEver(text)) {
            return Optional.empty();
        }
        String normalised = text.trim().replace(',', ' ').replaceAll("\\s+", " ");
        Matcher matcher = PART.matcher(normalised);

        ZonedDateTime when = ZonedDateTime.ofInstant(from, ZoneOffset.UTC);
        int consumedTo = 0;
        boolean any = false;

        while (matcher.find()) {
            if (matcher.start() != skipSpaces(normalised, consumedTo)) {
                return Optional.empty();
            }
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException tooBig) {
                return Optional.empty();
            }
            String unit = matcher.group(2);
            try {
                when = switch (unitOf(unit == null ? "s" : unit)) {
                    case SECONDS -> when.plusSeconds(amount);
                    case MINUTES -> when.plusMinutes(amount);
                    case HOURS -> when.plusHours(amount);
                    case DAYS -> when.plusDays(amount);
                    case WEEKS -> when.plusWeeks(amount);
                    // plusMonths does the day-of-month clamping for us, which is the whole reason
                    // this method exists rather than multiplying by thirty.
                    case MONTHS -> when.plusMonths(amount);
                    case YEARS -> when.plusYears(amount);
                };
            } catch (RuntimeException outOfRange) {
                return Optional.empty();
            }
            consumedTo = matcher.end();
            any = true;
        }
        if (!any || skipSpaces(normalised, consumedTo) != normalised.length()) {
            return Optional.empty();
        }
        return when.toInstant().isAfter(from) ? Optional.of(when.toInstant()) : Optional.empty();
    }

    // ---------------------------------------------------------------------------- writing

    /**
     * A length of time, the way somebody would say it.
     *
     * <p>Two parts at most. "1 year, 1 month, 4 days, 3 hours, 7 minutes" is a wall of text nobody
     * reads past the second comma of, so it stops there.
     *
     * @param duration null means for ever, which is a real answer rather than a missing one
     */
    public static String describe(Duration duration) {
        if (duration == null) {
            return "for ever";
        }
        if (duration.isZero() || duration.isNegative()) {
            return "no time at all";
        }

        List<String> parts = new ArrayList<>();
        long seconds = duration.getSeconds();

        long years = seconds / (DAYS_IN_A_YEAR * 86_400);
        seconds %= DAYS_IN_A_YEAR * 86_400;
        long months = seconds / (DAYS_IN_A_MONTH * 86_400);
        seconds %= DAYS_IN_A_MONTH * 86_400;
        long days = seconds / 86_400;
        seconds %= 86_400;
        long hours = seconds / 3_600;
        seconds %= 3_600;
        long minutes = seconds / 60;
        seconds %= 60;

        add(parts, years, "year");
        add(parts, months, "month");
        add(parts, days, "day");
        add(parts, hours, "hour");
        add(parts, minutes, "minute");
        add(parts, seconds, "second");

        return String.join(", ", parts.subList(0, Math.min(2, parts.size())));
    }

    private static void add(List<String> parts, long amount, String name) {
        if (amount > 0) {
            parts.add(amount + " " + name + (amount == 1 ? "" : "s"));
        }
    }

    /**
     * The same, short enough for a scoreboard or a button.
     *
     * <p>What this writes, {@link #parse} reads — so a length shown in a menu can be typed straight
     * back into a command, which is the sort of thing that is obvious only when it does not work.
     */
    public static String brief(Duration duration) {
        if (duration == null) {
            return "∞";
        }
        if (duration.isZero() || duration.isNegative()) {
            return "0s";
        }
        long seconds = duration.getSeconds();
        List<String> parts = new ArrayList<>();
        long days = seconds / 86_400;
        seconds %= 86_400;
        long hours = seconds / 3_600;
        seconds %= 3_600;
        long minutes = seconds / 60;
        seconds %= 60;

        if (days > 0) {
            parts.add(days + "d");
        }
        if (hours > 0) {
            parts.add(hours + "h");
        }
        if (minutes > 0) {
            parts.add(minutes + "m");
        }
        if (seconds > 0) {
            parts.add(seconds + "s");
        }
        return String.join(" ", parts);
    }
}

```

### src/main/java/de/raindancer/core/choose/Category.java
```java
package de.raindancer.core.ui.choose;

/**
 * The drawers everything is sorted into.
 *
 * <p>The creative inventory's, deliberately. It is the sorting every player on every server already
 * has in their head, and a cleverer one would only be a second thing to learn.
 *
 * <p>Each carries its own title and icon so no chooser anywhere has to invent them — which is how
 * two menus in two plugins end up showing the same category under two different names.
 */
public enum Category {

    BUILDING_BLOCKS("Building Blocks", "BRICKS"),
    DECORATIONS("Decoration", "PEONY"),
    REDSTONE("Redstone", "REDSTONE"),
    TRANSPORTATION("Transport", "POWERED_RAIL"),
    FOOD("Food", "GOLDEN_APPLE"),
    TOOLS("Tools", "IRON_AXE"),
    COMBAT("Combat", "GOLDEN_SWORD"),
    BREWING("Brewing", "BREWING_STAND"),
    /** Everything else. Never empty on a real server, and never a place things disappear into. */
    MISC("Everything Else", "LAVA_BUCKET");

    private final String title;
    private final String icon;

    Category(String title, String icon) {
        this.title = title;
        this.icon = icon;
    }

    /** What to write above it. */
    public String title() {
        return title;
    }

    /** The material to draw it with, by name — resolved by whoever is building the menu. */
    public String icon() {
        return icon;
    }
}

```

### src/main/java/de/raindancer/core/choose/Catalogue.java
```java
package de.raindancer.core.ui.choose;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Everything a server has, sorted into the drawers people already know.
 *
 * <h2>Why this exists</h2>
 * Because every plugin that lets somebody pick a block builds the same screen, and builds it badly.
 * A thousand-odd materials in enum order is not an order: {@code ACACIA_BOAT} sits between
 * {@code ACACIA_BUTTON} and {@code ACACIA_CHEST_BOAT}, so somebody looking for redstone scrolls past
 * eleven pages of wood. The two usual answers are both wrong — a hand-written shortlist is always
 * missing the block somebody wants, and the full list in enum order is unusable.
 *
 * <h2>Why the materials are injected</h2>
 * {@code Material.isItem()} needs the server's registry, so a catalogue that filtered with it could
 * only ever be tested on a running server. What is worth testing here is the sorting, so the list of
 * names comes in from outside and the sorting is ordinary code.
 *
 * <h2>How the sorting works</h2>
 * By name, in a fixed order of rules, most specific first. It is not perfect and cannot be — the
 * server does not publish its own creative tabs in a form that survives a version change — but it is
 * right for everything anybody actually goes looking for, and anything it is unsure of lands in
 * {@link Category#MISC} rather than vanishing.
 */
public final class Catalogue {

    private final Supplier<List<String>> materials;

    /** Worked out once and kept: this is a thousand strings through a dozen rules. */
    private volatile Map<Category, List<String>> sorted;

    /** @param materials the material names to sort — on a server, every one that is an item */
    public Catalogue(Supplier<List<String>> materials) {
        this.materials = materials;
    }

    /** Everything in one drawer, in alphabetical order. */
    public List<String> itemsIn(Category category) {
        return sorted().getOrDefault(category, List.of());
    }

    /** Everything, in one list, alphabetically. */
    public List<String> all() {
        List<String> everything = new ArrayList<>();
        sorted().values().forEach(everything::addAll);
        everything.sort(String::compareTo);
        return everything;
    }

    /** Which drawers actually have anything in them. */
    public List<Category> categories() {
        return List.of(Category.values()).stream()
                .filter(category -> !itemsIn(category).isEmpty())
                .toList();
    }

    /**
     * Everything whose name contains this.
     *
     * <p>Spaces work where underscores are, because nobody types an underscore into a search box, and
     * an exact match comes first — searching for the thing you named should not put four other things
     * above it.
     */
    public List<String> search(String text) {
        if (text == null || text.isBlank()) {
            return all();
        }
        String wanted = text.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return all().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains(wanted))
                .sorted(Comparator.comparing((String name) ->
                        name.equalsIgnoreCase(wanted) ? 0 : 1).thenComparing(name -> name))
                .toList();
    }

    private Map<Category, List<String>> sorted() {
        Map<Category, List<String>> known = sorted;
        if (known != null) {
            return known;
        }
        Map<Category, List<String>> built = new EnumMap<>(Category.class);
        for (String name : materials.get()) {
            built.computeIfAbsent(categoryOf(name), category -> new ArrayList<>()).add(name);
        }
        built.values().forEach(list -> list.sort(String::compareTo));
        sorted = built;
        return built;
    }

    /** Forgets the sorting — for a server that has just added or removed content. */
    public void refresh() {
        sorted = null;
    }

    // ---------------------------------------------------------------------------- the rules

    /**
     * Which drawer one material belongs in.
     *
     * <p>Order matters throughout: {@code REDSTONE_TORCH} has to be caught by redstone before torch
     * catches it for decoration, and {@code GOLDEN_CARROT} by food before {@code GOLDEN_} suggests
     * anything else. Each rule is here because something landed in the wrong place without it.
     */
    public static Category categoryOf(String material) {
        if (material == null || material.isBlank()) {
            return Category.MISC;
        }
        String name = material.toUpperCase(Locale.ROOT);

        if (isRedstone(name)) {
            return Category.REDSTONE;
        }
        if (isTransport(name)) {
            return Category.TRANSPORTATION;
        }
        if (isBrewing(name)) {
            return Category.BREWING;
        }
        if (isFood(name)) {
            return Category.FOOD;
        }
        if (isCombat(name)) {
            return Category.COMBAT;
        }
        if (isTool(name)) {
            return Category.TOOLS;
        }
        if (isDecoration(name)) {
            return Category.DECORATIONS;
        }
        if (isBuilding(name)) {
            return Category.BUILDING_BLOCKS;
        }
        return Category.MISC;
    }

    private static boolean isRedstone(String name) {
        return name.startsWith("REDSTONE") || name.endsWith("_BUTTON") || name.endsWith("_PLATE")
                || name.contains("PISTON") || name.contains("REPEATER")
                || name.contains("COMPARATOR") || name.contains("OBSERVER")
                || name.contains("DISPENSER") || name.contains("DROPPER")
                || name.contains("HOPPER") || name.contains("LEVER") || name.contains("TRIPWIRE")
                || name.contains("DAYLIGHT_DETECTOR") || name.contains("TARGET")
                || name.contains("SCULK_SENSOR") || name.contains("LECTERN")
                || name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR")
                || name.endsWith("_FENCE_GATE") || name.contains("NOTE_BLOCK")
                || name.contains("SLIME_BLOCK") || name.contains("HONEY_BLOCK")
                || name.contains("CRAFTER") || name.contains("COPPER_BULB");
    }

    private static boolean isTransport(String name) {
        return name.contains("MINECART") || name.endsWith("_BOAT") || name.endsWith("_RAFT")
                || name.endsWith("_RAIL") || name.equals("RAIL") || name.equals("ELYTRA")
                || name.equals("SADDLE") || name.contains("HORSE_ARMOR")
                || name.equals("CARROT_ON_A_STICK") || name.equals("WARPED_FUNGUS_ON_A_STICK");
    }

    private static boolean isBrewing(String name) {
        return name.contains("POTION") || name.equals("BREWING_STAND") || name.equals("CAULDRON")
                || name.equals("GLASS_BOTTLE") || name.equals("NETHER_WART")
                || name.equals("BLAZE_POWDER") || name.equals("FERMENTED_SPIDER_EYE")
                || name.equals("GLISTERING_MELON_SLICE") || name.equals("MAGMA_CREAM")
                || name.equals("GHAST_TEAR") || name.equals("DRAGON_BREATH")
                || name.equals("PHANTOM_MEMBRANE") || name.equals("RABBIT_FOOT")
                || name.equals("SPIDER_EYE") || name.equals("GUNPOWDER")
                || name.equals("REDSTONE_DUST") || name.equals("GLOWSTONE_DUST");
    }

    private static boolean isFood(String name) {
        return name.startsWith("COOKED_") || name.startsWith("RAW_")
                || name.equals("APPLE") || name.equals("GOLDEN_APPLE")
                || name.equals("ENCHANTED_GOLDEN_APPLE") || name.equals("BREAD")
                || name.equals("CARROT") || name.equals("GOLDEN_CARROT") || name.equals("POTATO")
                || name.equals("BAKED_POTATO") || name.equals("POISONOUS_POTATO")
                || name.equals("BEETROOT") || name.equals("BEETROOT_SOUP")
                || name.equals("MUSHROOM_STEW") || name.equals("RABBIT_STEW")
                || name.equals("SUSPICIOUS_STEW") || name.equals("MELON_SLICE")
                || name.equals("SWEET_BERRIES") || name.equals("GLOW_BERRIES")
                || name.equals("CHORUS_FRUIT") || name.equals("DRIED_KELP")
                || name.equals("HONEY_BOTTLE") || name.equals("MILK_BUCKET")
                || name.equals("PUMPKIN_PIE") || name.equals("CAKE") || name.equals("COOKIE")
                || name.equals("BEEF") || name.equals("PORKCHOP") || name.equals("CHICKEN")
                || name.equals("MUTTON") || name.equals("RABBIT") || name.equals("COD")
                || name.equals("SALMON") || name.equals("TROPICAL_FISH")
                || name.equals("PUFFERFISH") || name.equals("ROTTEN_FLESH");
    }

    private static boolean isCombat(String name) {
        return name.endsWith("_SWORD") || name.endsWith("_AXE") && name.contains("BATTLE")
                || name.equals("BOW") || name.equals("CROSSBOW") || name.equals("TRIDENT")
                || name.equals("ARROW") || name.endsWith("_ARROW") || name.equals("SHIELD")
                || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                || name.equals("TURTLE_HELMET") || name.equals("TOTEM_OF_UNDYING")
                || name.equals("FIREWORK_ROCKET") || name.equals("MACE")
                || name.equals("WIND_CHARGE") || name.contains("TNT");
    }

    private static boolean isTool(String name) {
        return name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE") || name.equals("SHEARS") || name.equals("FLINT_AND_STEEL")
                || name.equals("FISHING_ROD") || name.equals("COMPASS") || name.equals("CLOCK")
                || name.equals("SPYGLASS") || name.equals("BRUSH") || name.equals("LEAD")
                || name.equals("NAME_TAG") || name.endsWith("_BUCKET") || name.equals("BUCKET")
                || name.endsWith("_SIGN") || name.equals("BOOK") || name.equals("WRITABLE_BOOK")
                || name.endsWith("_BOOKSHELF") || name.contains("MAP");
    }

    private static boolean isDecoration(String name) {
        return name.endsWith("_SAPLING") || name.endsWith("_LEAVES") || name.endsWith("_BED")
                || name.equals("PAINTING") || name.equals("ITEM_FRAME")
                || name.equals("GLOW_ITEM_FRAME") || name.equals("FLOWER_POT")
                || name.equals("ARMOR_STAND") || name.endsWith("_BANNER")
                || name.endsWith("_CARPET") || name.endsWith("_CANDLE") || name.equals("CANDLE")
                || name.contains("TORCH") || name.contains("LANTERN") || name.equals("CHAIN")
                || name.endsWith("_HEAD") || name.endsWith("_SKULL") || name.contains("POTTED")
                || name.endsWith("_FLOWER") || name.equals("DANDELION") || name.equals("POPPY")
                || name.equals("PEONY") || name.equals("ROSE_BUSH") || name.equals("LILAC")
                || name.contains("CORAL") || name.contains("SHULKER_BOX")
                || name.contains("FLOWER") || name.contains("MUSHROOM") && !name.contains("STEW")
                || name.equals("VINE") || name.contains("SCULK") || name.contains("GLASS_PANE")
                || name.contains("CAMPFIRE") || name.equals("BEACON")
                || name.equals("CONDUIT") || name.equals("END_ROD");
    }

    private static boolean isBuilding(String name) {
        return name.endsWith("_PLANKS") || name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STAIRS") || name.endsWith("_SLAB") || name.endsWith("_WALL")
                || name.endsWith("_FENCE") || name.endsWith("_BRICKS") || name.endsWith("_BRICK")
                || name.endsWith("_CONCRETE") || name.endsWith("_TERRACOTTA")
                || name.endsWith("_WOOL") || name.endsWith("_GLASS") || name.endsWith("_ORE")
                || name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("SAND")
                || name.contains("DIRT") || name.contains("GRASS_BLOCK")
                || name.contains("COPPER") || name.contains("QUARTZ") || name.contains("PRISMARINE")
                || name.contains("NETHERRACK") || name.contains("BASALT")
                || name.contains("BLACKSTONE") || name.contains("PURPUR")
                || name.contains("END_STONE") || name.contains("OBSIDIAN")
                || name.endsWith("_BLOCK") || name.equals("GRAVEL") || name.equals("CLAY")
                || name.equals("SNOW") || name.equals("ICE") || name.equals("PACKED_ICE")
                || name.equals("BLUE_ICE") || name.equals("GLOWSTONE") || name.equals("SPONGE");
    }

    // ---------------------------------------------------------------------------- second level

    /**
     * Which family within its category a material belongs to — "Oak", "Deepslate", "Red", "Diamond".
     *
     * <p>The second level, and the one that decides whether a chooser is usable. "Building Blocks" on
     * a modern server is several hundred materials and eleven of every twelve are wood: somebody
     * looking for deepslate scrolls past acacia, bamboo, birch, cherry, crimson and dark oak first.
     * The creative inventory has exactly this problem and players only cope because they have
     * memorised where things are.
     *
     * <p>Order matters again, and for the same reason as {@link #categoryOf}: {@code DARK_OAK} has to
     * be recognised before {@code OAK}, and {@code LIGHT_BLUE} before {@code BLUE}, or a whole tree's
     * worth of blocks lands in the wrong drawer.
     */
    public static String groupOf(String material) {
        if (material == null || material.isBlank()) {
            return "Other";
        }
        String name = material.toUpperCase(Locale.ROOT);
        for (String family : FAMILIES) {
            if (name.startsWith(family + "_") || name.equals(family)
                    || name.contains("_" + family + "_") || name.endsWith("_" + family)) {
                return readable(family);
            }
        }
        return "Other";
    }

    /**
     * The families present in one category, in the order they should be shown.
     *
     * <p>A family holding one thing is folded back into "Other": clicking through to a page with a
     * single item on it is worse than a slightly longer list, and a grid of one-item pages is the
     * failure this whole second level exists to avoid.
     */
    public List<String> groupsIn(Category category) {
        Map<String, List<String>> grouped = groupedIn(category);
        List<String> names = new ArrayList<>(grouped.keySet());
        names.remove("Other");
        names.sort(String::compareTo);
        if (grouped.containsKey("Other")) {
            // Always last, whatever it is called: it is the drawer of leftovers and nobody looks
            // there first.
            names.add("Other");
        }
        return names;
    }

    /** Everything in one family of one category, alphabetically. */
    public List<String> itemsIn(Category category, String group) {
        return groupedIn(category).getOrDefault(group, List.of());
    }

    private Map<String, List<String>> groupedIn(Category category) {
        Map<String, List<String>> grouped = new java.util.LinkedHashMap<>();
        for (String material : itemsIn(category)) {
            grouped.computeIfAbsent(groupOf(material), family -> new ArrayList<>()).add(material);
        }
        // Fold the singletons together afterwards rather than while sorting: whether a family is
        // worth a page depends on how many ended up in it, which is not known until they all have.
        List<String> lonely = new ArrayList<>();
        grouped.entrySet().removeIf(entry -> {
            if (!entry.getKey().equals("Other") && entry.getValue().size() < 2) {
                lonely.addAll(entry.getValue());
                return true;
            }
            return false;
        });
        if (!lonely.isEmpty()) {
            grouped.computeIfAbsent("Other", family -> new ArrayList<>()).addAll(lonely);
        }
        grouped.values().forEach(list -> list.sort(String::compareTo));
        return grouped;
    }

    /**
     * The families a material name might belong to, longest first.
     *
     * <p>Longest first is load-bearing: {@code DARK_OAK} before {@code OAK}, {@code LIGHT_BLUE}
     * before {@code BLUE}, {@code POLISHED_BLACKSTONE} before {@code BLACKSTONE}.
     */
    private static final List<String> FAMILIES = List.of(
            // Compound names first, or the colour rule below eats half of them: RED_SANDSTONE is a
            // kind of sandstone, not a red thing, and RED_NETHER_BRICK is a kind of brick.
            "RED_SANDSTONE", "RED_NETHER_BRICK", "NETHER_BRICK",
            // Woods — the ones that swamp the building blocks tab.
            "DARK_OAK", "PALE_OAK", "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "MANGROVE",
            "CHERRY", "BAMBOO", "CRIMSON", "WARPED",
            // The sixteen colours, before the things that come in sixteen colours. Light ones first
            // so LIGHT_BLUE is not eaten by BLUE.
            "LIGHT_BLUE", "LIGHT_GRAY", "WHITE", "ORANGE", "MAGENTA", "YELLOW", "LIME", "PINK",
            "GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK",
            // Stones and their many polished, chiselled, cracked relatives.
            "POLISHED_BLACKSTONE", "BLACKSTONE", "COBBLED_DEEPSLATE", "DEEPSLATE", "SANDSTONE",
            "END_STONE", "STONE", "GRANITE", "DIORITE", "ANDESITE", "TUFF", "CALCITE", "BASALT",
            "PRISMARINE", "QUARTZ", "PURPUR", "NETHERRACK", "MUD_BRICK", "BRICK", "COPPER",
            "AMETHYST", "OBSIDIAN",
            // What tools and armour are made of. GOLDEN rather than GOLD: the items are GOLDEN_.
            "NETHERITE", "DIAMOND", "GOLDEN", "IRON", "CHAINMAIL", "WOODEN", "LEATHER", "TURTLE",
            // Creatures, for spawn eggs and heads and the rest.
            "ZOMBIE", "SKELETON", "CREEPER", "SPIDER", "ENDERMAN", "VILLAGER", "PIGLIN",
            // Odds and ends that still come in families. Last, because these are the ones a colour
            // or a wood should win against.
            "MUSIC_DISC", "POTTERY_SHERD", "SMITHING_TEMPLATE", "TERRACOTTA", "CONCRETE", "CANDLE",
            "GLASS", "WOOL", "CARPET", "BANNER", "SHULKER_BOX", "CORAL", "FROGLIGHT", "MINECART",
            "BOAT", "RAIL");

    // ---------------------------------------------------------------------------- presentation

    /**
     * The words that stay in capitals.
     *
     * <p>An allow-list rather than a rule about short words, which is what this was first: "NO",
     * "USE" and "HIT" are all three letters and all capitals in a sound key, and none of them is an
     * acronym. A deny-list of those would have been missing one for ever.
     */
    private static final java.util.Set<String> ACRONYMS = java.util.Set.of("TNT", "XP", "UI", "ID");

    /**
     * A material name, written the way a person would.
     *
     * <p>{@code DIAMOND_PICKAXE} becomes "Diamond Pickaxe" — but {@code TNT} stays {@code TNT},
     * because "Tnt" is the sort of small wrongness that makes a menu look machine-made.
     */
    public static String readable(String material) {
        if (material == null || material.isBlank()) {
            return "";
        }
        StringBuilder built = new StringBuilder();
        for (String word : material.trim().split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!built.isEmpty()) {
                built.append(' ');
            }
            if ("GOLDEN".equals(word)) {
                // The blocks say GOLD and the items say GOLDEN; a menu showing both is a menu that
                // looks like it has two kinds of gold in it.
                built.append("Gold");
            } else if (ACRONYMS.contains(word)) {
                built.append(word);
            } else {
                built.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return built.toString();
    }
}

```

### src/main/java/de/raindancer/core/choose/SoundFamily.java
```java
package de.raindancer.core.ui.choose;

import java.util.Locale;

/**
 * The families a sound key falls into.
 *
 * <p>Minecraft's sound names are already a hierarchy — {@code block.stone.break},
 * {@code entity.villager.no}, {@code ui.button.click} — so the sorting is the first word, and it is
 * the right sorting because it is the one the game itself uses.
 */
public enum SoundFamily {

    UI("Interface", "OAK_BUTTON", "ui."),
    BLOCK("Blocks", "STONE", "block."),
    ITEM("Items", "IRON_INGOT", "item."),
    ENTITY("Creatures", "ZOMBIE_HEAD", "entity."),
    MUSIC("Music", "MUSIC_DISC_CAT", "music.", "music_disc."),
    AMBIENT("Ambience", "FEATHER", "ambient.", "weather."),
    OTHER("Everything Else", "NOTE_BLOCK");

    private final String title;
    private final String icon;
    private final String[] prefixes;

    SoundFamily(String title, String icon, String... prefixes) {
        this.title = title;
        this.icon = icon;
        this.prefixes = prefixes;
    }

    public String title() {
        return title;
    }

    public String icon() {
        return icon;
    }

    /** Which family a sound key belongs to. Anything unrecognised is {@link #OTHER}, never dropped. */
    public static SoundFamily of(String key) {
        if (key == null || key.isBlank()) {
            return OTHER;
        }
        String name = key.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        for (SoundFamily family : values()) {
            for (String prefix : family.prefixes) {
                if (name.startsWith(prefix)) {
                    return family;
                }
            }
        }
        return OTHER;
    }
}

```

### src/main/java/de/raindancer/core/choose/SoundCatalogue.java
```java
package de.raindancer.core.ui.choose;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Every sound a server knows, sorted so somebody can find one.
 *
 * <p>The same problem as {@link Catalogue} and the same answer. There are well over a thousand sound
 * keys; a plugin that wants to let an owner pick one either ships a list of twelve or shows all of
 * them in registry order, and neither is a chooser.
 *
 * <p>Sorted by the first word of the key, because Minecraft's own names are already a hierarchy and
 * inventing a different one would only be a second thing to learn.
 */
public final class SoundCatalogue {

    private final Supplier<List<String>> sounds;
    private volatile Map<SoundFamily, List<String>> sorted;

    /** @param sounds the sound keys this server has — the registry, on a real one */
    public SoundCatalogue(Supplier<List<String>> sounds) {
        this.sounds = sounds;
    }

    public List<String> inFamily(SoundFamily family) {
        return sorted().getOrDefault(family, List.of());
    }

    public List<SoundFamily> families() {
        return List.of(SoundFamily.values()).stream()
                .filter(family -> !inFamily(family).isEmpty())
                .toList();
    }

    public List<String> all() {
        List<String> everything = new ArrayList<>();
        sorted().values().forEach(everything::addAll);
        everything.sort(String::compareTo);
        return everything;
    }

    /** Every sound whose key contains this; an exact match first. */
    public List<String> search(String text) {
        if (text == null || text.isBlank()) {
            return all();
        }
        String wanted = text.trim().toLowerCase(Locale.ROOT).replace(' ', '.');
        return all().stream()
                .filter(key -> key.toLowerCase(Locale.ROOT).contains(wanted))
                .sorted(Comparator.comparing((String key) ->
                        key.equalsIgnoreCase(wanted) ? 0 : 1).thenComparing(key -> key))
                .toList();
    }

    /**
     * The thing that makes the noise, as a material name.
     *
     * <h2>Why this is not just a note block</h2>
     * Because a grid of forty-five identical note blocks is not a chooser — it is a list of names in
     * a costume, and picking from it means reading {@code block.amethyst_block.chime} and imagining.
     * The key already says what makes the sound, so the icon can be that: the amethyst block, the
     * bell, the anvil, the zombie.
     *
     * <p>Taken from the middle of the key, which is where the game puts the thing:
     * {@code block.<b>bell</b>.use}, {@code entity.<b>zombie</b>.ambient}. A creature gets its spawn
     * egg, one without a spawn egg gets its head, and anything left over gets something that suits
     * its family rather than nothing.
     *
     * <p>Returns a name rather than a {@code Material} on purpose: resolving one needs the server's
     * registry, and the naming is the part worth testing.
     */
    public static String iconFor(String key) {
        if (key == null || key.isBlank()) {
            return "NOTE_BLOCK";
        }
        String name = key.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        SoundFamily family = SoundFamily.of(name);
        String[] parts = name.split("\\.");
        String subject = parts.length >= 2 ? parts[1].toUpperCase(Locale.ROOT) : "";

        return switch (family) {
            // A music disc is named after the track, so the key is very nearly the item already.
            case MUSIC -> name.startsWith("music_disc.")
                    ? "MUSIC_DISC_" + subject : "MUSIC_DISC_" + pickDisc(name);
            case ENTITY -> creature(subject);
            case UI -> "OAK_BUTTON";
            case AMBIENT -> name.startsWith("weather.") ? "WATER_BUCKET" : "DEEPSLATE";
            // Blocks and items are the easy case: the middle word usually is the material.
            case BLOCK, ITEM, OTHER -> subject.isEmpty() ? "NOTE_BLOCK" : subject;
        };
    }

    /**
     * A creature's icon: its spawn egg, or its head, or a failing that something of its own.
     *
     * <p>The exceptions are the mobs with no spawn egg. A note block standing in for the ender
     * dragon would be exactly the wrongness this method exists to remove.
     */
    private static String creature(String subject) {
        return switch (subject) {
            case "PLAYER" -> "PLAYER_HEAD";
            case "ENDER_DRAGON" -> "DRAGON_HEAD";
            case "WITHER" -> "WITHER_SKELETON_SKULL";
            case "ILLUSIONER", "GIANT", "ZOMBIE_HORSE", "SKELETON_HORSE" -> "BONE";
            case "ITEM", "ITEM_FRAME" -> "ITEM_FRAME";
            case "ARROW", "SPECTRAL_ARROW" -> "ARROW";
            case "BOAT", "CHEST_BOAT" -> "OAK_BOAT";
            case "MINECART" -> "MINECART";
            case "LIGHTNING_BOLT" -> "LIGHTNING_ROD";
            case "EXPERIENCE_ORB", "EXPERIENCE_BOTTLE" -> "EXPERIENCE_BOTTLE";
            case "GENERIC", "" -> "NOTE_BLOCK";
            default -> subject + "_SPAWN_EGG";
        };
    }

    /** Something to stand for a music track that is not a disc. */
    private static String pickDisc(String name) {
        return name.contains("nether") || name.contains("end") ? "PIGSTEP" : "CAT";
    }

    /**
     * A sound key, written for a menu.
     *
     * <p>{@code block.note_block.bell} becomes "Note Block Bell": the family is already the page
     * somebody is on, so repeating it in every line is noise.
     */
    public static String readable(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String name = key.replace("minecraft:", "");
        int firstDot = name.indexOf('.');
        String withoutFamily = firstDot > 0 ? name.substring(firstDot + 1) : name;
        return Catalogue.readable(withoutFamily.replace('.', '_').toUpperCase(Locale.ROOT));
    }

    private Map<SoundFamily, List<String>> sorted() {
        Map<SoundFamily, List<String>> known = sorted;
        if (known != null) {
            return known;
        }
        Map<SoundFamily, List<String>> built = new EnumMap<>(SoundFamily.class);
        for (String key : sounds.get()) {
            built.computeIfAbsent(SoundFamily.of(key), family -> new ArrayList<>()).add(key);
        }
        built.values().forEach(list -> list.sort(String::compareTo));
        sorted = built;
        return built;
    }

    public void refresh() {
        sorted = null;
    }
}

```

### src/main/java/de/raindancer/core/choose/ItemChooser.java
```java
package de.raindancer.core.ui.choose;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * Picking a block or item, out of everything the server has.
 *
 * <h2>Why Core ships the screen and not just the list</h2>
 * Because the screen is where it goes wrong. Every plugin that needs one writes the same paged grid,
 * and every one of them writes it slightly differently: this one has no search, that one has no back
 * button, the third shows a thousand materials in enum order. A player who has learned one has
 * learned none of the others.
 *
 * <p>So a plugin says what it wants a block <em>for</em>, and gets a chooser:
 *
 * <pre>{@code
 * new ItemChooser(player, brand, parentMenu, "Pick an icon", chosen -> {
 *     settings.set("icon", chosen.name());
 * }).open();
 * }</pre>
 *
 * <p>Sorted into the creative inventory's own drawers, because that is the sorting every player
 * already has in their head.
 */
public final class ItemChooser extends PaginatedMenu<Category> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String heading;
    private final Consumer<Material> chosen;
    private final Catalogue catalogue;

    /**
     * @param heading what the player is picking a block for — shown as the window's title
     * @param chosen  called with what they picked; the menu closes itself first
     */
    public ItemChooser(Player viewer, Brand brand, Menu parent, String heading,
                       Consumer<Material> chosen) {
        this(viewer, brand, parent, heading, chosen, everythingOnThisServer());
    }

    /** The same, over a list somebody else decided — for a plugin offering a shortlist. */
    public ItemChooser(Player viewer, Brand brand, Menu parent, String heading,
                       Consumer<Material> chosen, Catalogue catalogue) {
        super(viewer, brand, parent);
        this.heading = heading == null || heading.isBlank() ? "Choose a block" : heading;
        this.chosen = chosen;
        this.catalogue = catalogue;
    }

    /**
     * A cue for whoever is looking at this window.
     *
     * <p>Through Core rather than {@code playSound}, so a server owner who has rebound the click has
     * rebound this one too — which is the whole point of there being named cues at all.
     */
    private void play(String cue) {
        if (de.raindancer.core.RainsCore.isAvailable()) {
            de.raindancer.core.RainsCore.get().effects().play(viewer().getUniqueId(), cue);
        }
    }

    /**
     * Every material this server would let somebody hold.
     *
     * <p>{@code isItem} needs the registry, which is why this is here and not in {@link Catalogue}:
     * the sorting is testable without a server and this is not.
     */
    public static Catalogue everythingOnThisServer() {
        return new Catalogue(() -> java.util.Arrays.stream(Material.values())
                .filter(material -> !material.isLegacy())
                .filter(Material::isItem)
                .map(Enum::name)
                .toList());
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + heading);
    }

    @Override
    protected List<Category> entries() {
        return catalogue.categories();
    }

    @Override
    protected ItemStack icon(Category category) {
        Material material = Material.matchMaterial(category.icon());
        return Icons.of(material == null ? Material.CHEST : material,
                "<" + Style.itemName() + ">" + category.title(),
                "<" + Style.itemLore() + ">" + catalogue.itemsIn(category).size() + " to choose from",
                "",
                "<" + Style.itemLore() + ">Click to open");
    }

    @Override
    protected void onClick(Category category, InventoryClickEvent event) {
        play(Cues.PAGE);
        new Families(viewer(), brand(), this, category).open();
    }

    /**
     * One drawer's families — the second level.
     *
     * <p>Without this, "Building Blocks" on a modern server is several hundred materials and eleven
     * of every twelve are wood, so anybody looking for deepslate scrolls past six kinds of tree
     * first. A family holding only one thing is folded into "Other" rather than given a page of its
     * own, because clicking through to a page with a single item on it is worse than a longer list.
     */
    private final class Families extends PaginatedMenu<String> {

        private final Category category;

        private Families(Player viewer, Brand brand, Menu parent, Category category) {
            super(viewer, brand, parent);
            this.category = category;
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<" + Style.titleLabel() + ">" + category.title());
        }

        @Override
        protected List<String> entries() {
            return catalogue.groupsIn(category);
        }

        @Override
        protected ItemStack icon(String group) {
            List<String> items = catalogue.itemsIn(category, group);
            // Drawn with the first thing inside it, so the button looks like what it holds rather
            // than like a folder. A family of oak stairs shows oak stairs.
            Material found = items.isEmpty() ? null : Material.matchMaterial(items.getFirst());
            return Icons.of(found == null ? Material.CHEST : found,
                    "<" + Style.itemName() + ">" + group,
                    "<" + Style.itemLore() + ">" + items.size() + " to choose from",
                    "",
                    "<" + Style.itemLore() + ">Click to open");
        }

        @Override
        protected void onClick(String group, InventoryClickEvent event) {
            play(Cues.PAGE);
            new WithinGroup(viewer(), brand(), this, category, group).open();
        }
    }

    /** One family's worth, which is the page anybody actually picks from. */
    private final class WithinGroup extends PaginatedMenu<String> {

        private final Category category;
        private final String group;

        private WithinGroup(Player viewer, Brand brand, Menu parent, Category category,
                            String group) {
            super(viewer, brand, parent);
            this.category = category;
            this.group = group;
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<" + Style.titleLabel() + ">" + group
                    + " <" + Style.itemLore() + ">· " + category.title());
        }

        @Override
        protected List<String> entries() {
            return catalogue.itemsIn(category, group);
        }

        @Override
        protected ItemStack icon(String material) {
            Material found = Material.matchMaterial(material);
            // A name the catalogue knows but this server cannot make a stack of is drawn as a
            // barrier rather than dropped: a hole in the grid is worse than an item saying why.
            return found == null
                    ? Icons.of(Material.BARRIER, "<" + Style.bad() + ">" + Catalogue.readable(material),
                            "<" + Style.itemLore() + ">This server has no such block")
                    : Icons.of(found, "<" + Style.itemName() + ">" + Catalogue.readable(material),
                            "<" + Style.itemLore() + ">Click to choose");
        }

        @Override
        protected void onClick(String material, InventoryClickEvent event) {
            Material found = Material.matchMaterial(material);
            if (found == null) {
                play(Cues.NO);
                return;
            }
            play(Cues.OK);
            // Closed first: a callback that opens another window would otherwise be fighting this
            // one for the same screen, and the player would see whichever won.
            viewer().closeInventory();
            if (chosen != null) {
                chosen.accept(found);
            }
        }
    }
}

```

### src/main/java/de/raindancer/core/choose/SoundChooser.java
```java
package de.raindancer.core.ui.choose;

import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.effect.SoundCue;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * Picking a sound, out of the thousand-odd a server knows.
 *
 * <h2>The part that makes it usable</h2>
 * Clicking one <em>plays</em> it. A list of names like {@code block.amethyst_block.chime} is not a
 * chooser — nobody knows what any of them sound like, and picking by reading is picking at random.
 * Left-click hears it, right-click takes it.
 *
 * <p>Grouped by the first word of the key, because Minecraft's own names are already a hierarchy and
 * a different one would only be a second thing to learn.
 */
public final class SoundChooser extends PaginatedMenu<SoundFamily> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String heading;
    private final Consumer<String> chosen;
    private final SoundCatalogue catalogue;

    /**
     * @param heading what they are picking a sound for
     * @param chosen  called with the sound's key; the menu closes itself first
     */
    public SoundChooser(Player viewer, Brand brand, Menu parent, String heading,
                        Consumer<String> chosen) {
        this(viewer, brand, parent, heading, chosen, everythingOnThisServer());
    }

    public SoundChooser(Player viewer, Brand brand, Menu parent, String heading,
                        Consumer<String> chosen, SoundCatalogue catalogue) {
        super(viewer, brand, parent);
        this.heading = heading == null || heading.isBlank() ? "Choose a sound" : heading;
        this.chosen = chosen;
        this.catalogue = catalogue;
    }

    /**
     * Every sound this server has.
     *
     * <p>From the registry rather than the {@code Sound} enum, so a sound added by a resource pack or
     * by a newer version is in the list without this class being changed.
     */
    public static SoundCatalogue everythingOnThisServer() {
        return new SoundCatalogue(() -> {
            List<String> keys = new java.util.ArrayList<>();
            Registry.SOUNDS.forEach(sound -> {
                NamespacedKey key = sound.getKey();
                keys.add(key.getNamespace().equals("minecraft")
                        ? key.getKey() : key.toString());
            });
            return keys;
        });
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + heading);
    }

    @Override
    protected List<SoundFamily> entries() {
        return catalogue.families();
    }

    @Override
    protected ItemStack icon(SoundFamily family) {
        Material material = Material.matchMaterial(family.icon());
        return Icons.of(material == null ? Material.NOTE_BLOCK : material,
                "<" + Style.itemName() + ">" + family.title(),
                "<" + Style.itemLore() + ">" + catalogue.inFamily(family).size() + " sounds",
                "",
                "<" + Style.itemLore() + ">Click to open");
    }

    @Override
    protected void onClick(SoundFamily family, InventoryClickEvent event) {
        new WithinFamily(viewer(), brand(), this, family).open();
    }

    /** One family's sounds — where the listening happens. */
    private final class WithinFamily extends PaginatedMenu<String> {

        private final SoundFamily family;

        private WithinFamily(Player viewer, Brand brand, Menu parent, SoundFamily family) {
            super(viewer, brand, parent);
            this.family = family;
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<" + Style.titleLabel() + ">" + family.title());
        }

        @Override
        protected List<String> entries() {
            return catalogue.inFamily(family);
        }

        @Override
        protected ItemStack icon(String key) {
            // Drawn as the thing that makes the noise — the amethyst block, the bell, the zombie's
            // egg. A grid of identical note blocks is a list of names in a costume, not a chooser.
            Material face = Material.matchMaterial(SoundCatalogue.iconFor(key));
            return Icons.of(face == null ? Material.NOTE_BLOCK : face,
                    "<" + Style.itemName() + ">" + SoundCatalogue.readable(key),
                    "<" + Style.itemLore() + ">" + key,
                    "",
                    "<" + Style.itemLore() + ">Left-click to hear it",
                    "<" + Style.itemLore() + ">Right-click to choose it");
        }

        @Override
        protected void onClick(String key, InventoryClickEvent event) {
            if (event.isRightClick()) {
                viewer().closeInventory();
                if (chosen != null) {
                    chosen.accept(key);
                }
                return;
            }
            // Straight to the player rather than through a named cue: this is the raw sound being
            // auditioned, and running it through the vocabulary would play whatever that name is
            // bound to instead of the one being pointed at.
            if (RainsCore.isAvailable()) {
                viewer().playSound(viewer().getLocation(), key, 1f, 1f);
            }
        }
    }
}

```

### src/main/java/de/raindancer/core/choose/EffectChooser.java
```java
package de.raindancer.core.ui.choose;

import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.effect.Effect;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * Picking one of the named cues — and hearing it before you commit.
 *
 * <p>The chooser a settings page wants: "which effect should a successful claim play?" is a question
 * about meanings, not about sound keys, and the answer should be one of the names every plugin
 * already shares so that rebinding it later changes this too.
 *
 * <p>Left-click plays it, right-click picks it. Same as the sound chooser, for the same reason:
 * choosing by reading a list of names is choosing at random.
 */
public final class EffectChooser extends PaginatedMenu<String> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String heading;
    private final Consumer<String> chosen;
    private final Effects effects;

    public EffectChooser(Player viewer, Brand brand, Menu parent, String heading,
                         Consumer<String> chosen) {
        super(viewer, brand, parent);
        this.heading = heading == null || heading.isBlank() ? "Choose an effect" : heading;
        this.chosen = chosen;
        this.effects = RainsCore.isAvailable() ? RainsCore.get().effects() : null;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + heading);
    }

    @Override
    protected List<String> entries() {
        return effects == null ? List.of() : List.copyOf(effects.all().keySet());
    }

    @Override
    protected ItemStack icon(String cue) {
        Effect effect = effects == null ? null : effects.all().get(cue);
        String sound = effect == null || effect.sound() == null ? "silent" : effect.sound().key();
        String particles = effect == null || effect.particles() == null
                ? "no particles" : effect.particles().particle().toLowerCase(java.util.Locale.ROOT);
        return Icons.of(iconFor(cue),
                "<" + Style.itemName() + ">" + Catalogue.readable(
                        cue.substring(cue.indexOf(':') + 1).replace('-', '_').toUpperCase(
                                java.util.Locale.ROOT)),
                "<" + Style.itemLore() + ">" + cue,
                "<" + Style.itemLore() + ">" + sound,
                "<" + Style.itemLore() + ">" + particles,
                "",
                "<" + Style.itemLore() + ">Left-click to try it",
                "<" + Style.itemLore() + ">Right-click to choose it");
    }

    /**
     * Something that looks like what the cue means.
     *
     * <p>Guessed from the name rather than configured, because a cue a plugin invented this morning
     * still has to have an icon, and a grid of identical note blocks is not a chooser.
     */
    private static Material iconFor(String cue) {
        String name = cue.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("teleport")) {
            return Material.ENDER_PEARL;
        }
        if (name.contains("no") || name.contains("error") || name.contains("cooldown")) {
            return Material.BARRIER;
        }
        if (name.contains("ok") || name.contains("earn") || name.contains("reward")) {
            return Material.EMERALD;
        }
        if (name.contains("heal")) {
            return Material.GOLDEN_APPLE;
        }
        if (name.contains("hurt")) {
            return Material.IRON_SWORD;
        }
        if (name.contains("magic") || name.contains("ability")) {
            return Material.ENCHANTED_BOOK;
        }
        if (name.contains("open") || name.contains("close") || name.contains("page")) {
            return Material.BOOK;
        }
        if (name.contains("countdown")) {
            return Material.CLOCK;
        }
        if (name.contains("summon")) {
            return Material.EGG;
        }
        if (name.contains("vanish")) {
            return Material.GUNPOWDER;
        }
        return Material.NOTE_BLOCK;
    }

    @Override
    protected void onClick(String cue, InventoryClickEvent event) {
        if (event.isRightClick()) {
            viewer().closeInventory();
            if (chosen != null) {
                chosen.accept(cue);
            }
            return;
        }
        if (effects != null) {
            effects.play(viewer().getUniqueId(), cue);
        }
    }
}

```

### src/main/java/de/raindancer/core/choose/ParticleGroup.java
```java
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

```

### src/main/java/de/raindancer/core/choose/ParticleCatalogue.java
```java
package de.raindancer.core.ui.choose;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Every particle a server knows, sorted and given a face.
 *
 * <h2>Why this is harder than the sound catalogue</h2>
 * Because a sound key says what makes it — {@code block.bell.use} is a bell — and a particle name
 * does not. A list of {@code CRIT}, {@code DUST_PLUME}, {@code SCULK_CHARGE_POP} is a vocabulary
 * test, so both the grouping and the icon have to come from what the particle is <em>for</em>.
 *
 * <h2>The trap this exists to mark</h2>
 * Some particles do nothing at all unless they are given more than a name: {@code DUST} needs a
 * colour, {@code BLOCK} and {@code ITEM} need something to be made of. A chooser that offers them
 * like any other produces a setting that silently spawns nothing, which is the worst kind of
 * setting. {@link #needsExtraData} is how a menu can say so.
 */
public final class ParticleCatalogue {

    private final Supplier<List<String>> particles;
    private volatile Map<ParticleGroup, List<String>> sorted;

    public ParticleCatalogue(Supplier<List<String>> particles) {
        this.particles = particles;
    }

    public List<String> inGroup(ParticleGroup group) {
        return sorted().getOrDefault(group, List.of());
    }

    public List<ParticleGroup> groups() {
        return ParticleGroup.ordered().stream()
                .filter(group -> !inGroup(group).isEmpty())
                .toList();
    }

    public List<String> all() {
        List<String> everything = new ArrayList<>();
        sorted().values().forEach(everything::addAll);
        everything.sort(String::compareTo);
        return everything;
    }

    /** Every particle whose name contains this; an exact match first. */
    public List<String> search(String text) {
        if (text == null || text.isBlank()) {
            return all();
        }
        String wanted = text.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return all().stream()
                .filter(name -> name.contains(wanted))
                .sorted(Comparator.comparing((String name) ->
                        name.equals(wanted) ? 0 : 1).thenComparing(name -> name))
                .toList();
    }

    public void refresh() {
        sorted = null;
    }

    private Map<ParticleGroup, List<String>> sorted() {
        Map<ParticleGroup, List<String>> known = sorted;
        if (known != null) {
            return known;
        }
        Map<ParticleGroup, List<String>> built = new EnumMap<>(ParticleGroup.class);
        for (String particle : particles.get()) {
            built.computeIfAbsent(groupOf(particle), group -> new ArrayList<>()).add(particle);
        }
        built.values().forEach(list -> list.sort(String::compareTo));
        sorted = built;
        return built;
    }

    // ---------------------------------------------------------------------------- the rules

    /**
     * Which drawer a particle belongs in.
     *
     * <p>Order matters: the colourable ones are caught first because {@code DUST_COLOR_TRANSITION}
     * would otherwise be read as dust-in-general, and combat before magic because
     * {@code ENCHANTED_HIT} is something that happens in a fight.
     */
    public static ParticleGroup groupOf(String particle) {
        String name = ParticleGroup.normalise(particle);
        if (name.isEmpty()) {
            return ParticleGroup.OTHER;
        }
        if (needsExtraData(name) && !name.startsWith("BLOCK") && !name.startsWith("ITEM")
                && !name.startsWith("FALLING_DUST")) {
            return ParticleGroup.COLOURED;
        }
        if (name.startsWith("BLOCK") || name.startsWith("ITEM") || name.equals("FALLING_DUST")) {
            return ParticleGroup.BLOCKS;
        }
        if (contains(name, "FLAME", "LAVA", "SMOKE", "FIRE", "ASH_", "CAMPFIRE", "SMALL_FLAME",
                "SOUL", "SINGE", "EMBER")) {
            // SOUL is here rather than in magic because every particle with it in the name is a
            // soul *fire* one.
            return ParticleGroup.FIRE;
        }
        if (contains(name, "WATER", "BUBBLE", "SPLASH", "RAIN", "DRIP", "FISHING", "NAUTILUS",
                "UNDERWATER", "CURRENT")) {
            return name.equals("RAIN") ? ParticleGroup.WEATHER : ParticleGroup.WATER;
        }
        if (contains(name, "CRIT", "DAMAGE", "SWEEP", "EXPLOSION", "FLASH", "ANGRY_", "SONIC")) {
            return name.startsWith("ANGRY") ? ParticleGroup.EMOTES : ParticleGroup.COMBAT;
        }
        if (contains(name, "HEART", "VILLAGER", "COMPOSTER", "NOTE", "SPIT", "MOOD")) {
            return ParticleGroup.EMOTES;
        }
        if (contains(name, "ENCHANT", "PORTAL", "END_ROD", "WITCH", "DRAGON", "SPELL", "TOTEM",
                "REVERSE", "GLOW", "SHRIEK", "WARPED", "RAID", "OMINOUS", "TRIAL")) {
            return ParticleGroup.MAGIC;
        }
        if (contains(name, "CLOUD", "SNOW", "ASH", "SPORE", "POLLEN", "MYCELIUM", "WIND", "GUST")) {
            return ParticleGroup.WEATHER;
        }
        return ParticleGroup.OTHER;
    }

    private static boolean contains(String name, String... needles) {
        for (String needle : needles) {
            if (name.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The particles that spawn nothing unless they are given a colour or a material.
     *
     * <p>Worth marking rather than hiding: an owner who picks one and sees nothing has no way of
     * telling that from a broken plugin.
     */
    private static final Set<String> NEEDS_DATA = Set.of(
            "DUST", "DUST_COLOR_TRANSITION", "DUST_PILLAR", "BLOCK", "BLOCK_MARKER",
            "BLOCK_CRUMBLE", "FALLING_DUST", "ITEM", "ENTITY_EFFECT", "TRAIL", "SHRIEK",
            "SCULK_CHARGE", "VIBRATION", "TINTED_LEAVES");

    public static boolean needsExtraData(String particle) {
        return NEEDS_DATA.contains(ParticleGroup.normalise(particle));
    }

    // ---------------------------------------------------------------------------- the faces

    /**
     * Something to draw a particle as.
     *
     * <p>By name where the name says something — flame is a fire charge, lava is a lava bucket — and
     * by its group where it does not. A grid of identical grey panes with words on is the failure
     * this avoids; falling back to the group's own icon at least sorts the page visually.
     */
    public static String iconFor(String particle) {
        String name = ParticleGroup.normalise(particle);
        if (name.isEmpty()) {
            return ParticleGroup.OTHER.icon();
        }
        return switch (name) {
            case "FLAME", "SMALL_FLAME" -> "FIRE_CHARGE";
            case "SOUL_FIRE_FLAME", "SOUL" -> "SOUL_TORCH";
            case "LAVA" -> "LAVA_BUCKET";
            case "SMOKE", "LARGE_SMOKE" -> "COAL";
            case "CAMPFIRE_COSY_SMOKE", "CAMPFIRE_SIGNAL_SMOKE" -> "CAMPFIRE";
            case "HEART" -> "POPPY";
            case "ANGRY_VILLAGER" -> "IRON_SWORD";
            case "HAPPY_VILLAGER" -> "EMERALD";
            case "NOTE" -> "NOTE_BLOCK";
            case "PORTAL", "REVERSE_PORTAL" -> "OBSIDIAN";
            case "ENCHANT", "ENCHANTED_HIT" -> "ENCHANTING_TABLE";
            case "END_ROD" -> "END_ROD";
            case "WITCH" -> "POTION";
            case "DRAGON_BREATH" -> "DRAGON_BREATH";
            case "TOTEM_OF_UNDYING" -> "TOTEM_OF_UNDYING";
            case "CRIT" -> "IRON_SWORD";
            case "EXPLOSION", "EXPLOSION_EMITTER" -> "TNT";
            case "SNOWFLAKE" -> "SNOWBALL";
            case "CLOUD" -> "WHITE_WOOL";
            case "BUBBLE", "BUBBLE_POP", "BUBBLE_COLUMN_UP" -> "WATER_BUCKET";
            case "SPLASH", "RAIN" -> "WATER_BUCKET";
            case "FISHING" -> "FISHING_ROD";
            case "DUST", "DUST_COLOR_TRANSITION" -> "RED_DYE";
            case "BLOCK", "BLOCK_MARKER", "FALLING_DUST" -> "GRASS_BLOCK";
            case "ITEM" -> "ITEM_FRAME";
            default -> groupOf(name).icon();
        };
    }

    /** A particle name, written for a menu. */
    public static String readable(String particle) {
        return Catalogue.readable(ParticleGroup.normalise(particle));
    }
}

```

### src/main/java/de/raindancer/core/choose/ParticleChooser.java
```java
package de.raindancer.core.ui.choose;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * Picking a particle — and seeing it before you commit.
 *
 * <h2>The part that makes it a chooser rather than a list</h2>
 * Left-click <em>spawns it in front of you</em>. Nobody knows what {@code SCULK_CHARGE_POP} looks
 * like, and picking one by reading the name is picking at random; the same problem as sounds and the
 * same answer.
 *
 * <p>Right-click takes it. A particle that will not show up without a colour or a block says so on
 * its own button, because a setting that silently spawns nothing is the worst kind to hand somebody.
 */
public final class ParticleChooser extends PaginatedMenu<ParticleGroup> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String heading;
    private final Consumer<String> chosen;
    private final ParticleCatalogue catalogue;

    public ParticleChooser(Player viewer, Brand brand, Menu parent, String heading,
                           Consumer<String> chosen) {
        this(viewer, brand, parent, heading, chosen, everythingOnThisServer());
    }

    public ParticleChooser(Player viewer, Brand brand, Menu parent, String heading,
                           Consumer<String> chosen, ParticleCatalogue catalogue) {
        super(viewer, brand, parent);
        this.heading = heading == null || heading.isBlank() ? "Choose a particle" : heading;
        this.chosen = chosen;
        this.catalogue = catalogue;
    }

    /** Every particle this server has. */
    public static ParticleCatalogue everythingOnThisServer() {
        return new ParticleCatalogue(() -> java.util.Arrays.stream(Particle.values())
                .map(Enum::name)
                .toList());
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + heading);
    }

    @Override
    protected List<ParticleGroup> entries() {
        return catalogue.groups();
    }

    @Override
    protected ItemStack icon(ParticleGroup group) {
        Material material = Material.matchMaterial(group.icon());
        return Icons.of(material == null ? Material.GLASS : material,
                "<" + Style.itemName() + ">" + group.title(),
                "<" + Style.itemLore() + ">" + catalogue.inGroup(group).size() + " particles",
                "",
                "<" + Style.itemLore() + ">Click to open");
    }

    @Override
    protected void onClick(ParticleGroup group, InventoryClickEvent event) {
        new WithinGroup(viewer(), brand(), this, group).open();
    }

    /** One group's particles — where the looking happens. */
    private final class WithinGroup extends PaginatedMenu<String> {

        private final ParticleGroup group;

        private WithinGroup(Player viewer, Brand brand, Menu parent, ParticleGroup group) {
            super(viewer, brand, parent);
            this.group = group;
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<" + Style.titleLabel() + ">" + group.title());
        }

        @Override
        protected List<String> entries() {
            return catalogue.inGroup(group);
        }

        @Override
        protected ItemStack icon(String particle) {
            Material face = Material.matchMaterial(ParticleCatalogue.iconFor(particle));
            List<String> lore = new java.util.ArrayList<>(List.of(
                    "<" + Style.itemLore() + ">" + particle));
            if (ParticleCatalogue.needsExtraData(particle)) {
                // Said on the button rather than discovered afterwards: this one spawns nothing at
                // all unless whatever uses it supplies a colour or a block.
                lore.add("<" + Style.warn() + ">Needs a colour or a block to show up");
            }
            lore.add("");
            lore.add("<" + Style.itemLore() + ">Left-click to see it");
            lore.add("<" + Style.itemLore() + ">Right-click to choose it");
            return Icons.of(face == null ? Material.GLASS : face,
                    "<" + Style.itemName() + ">" + ParticleCatalogue.readable(particle), lore);
        }

        @Override
        protected void onClick(String particle, InventoryClickEvent event) {
            if (event.isRightClick()) {
                viewer().closeInventory();
                if (chosen != null) {
                    chosen.accept(particle);
                }
                return;
            }
            preview(particle);
        }

        /**
         * Shows the particle in front of the player, through the open window.
         *
         * <p>In front rather than at their feet: a menu covers most of the screen, and a particle
         * spawned underneath it is one the player cannot see, which looks exactly like one that did
         * not spawn.
         */
        private void preview(String particle) {
            Particle found;
            try {
                found = Particle.valueOf(particle);
            } catch (IllegalArgumentException gone) {
                return;
            }
            if (ParticleCatalogue.needsExtraData(particle)) {
                // Skipped rather than attempted: spawning one of these without its data throws on
                // some versions and silently does nothing on others, and neither is a preview.
                return;
            }
            var at = viewer().getEyeLocation().add(viewer().getLocation().getDirection().multiply(2));
            viewer().spawnParticle(found, at, 30, 0.4, 0.4, 0.4, 0.02);
        }
    }
}

```

### src/main/java/de/raindancer/core/choose/PlayerEntry.java
```java
package de.raindancer.core.ui.choose;

import de.raindancer.core.world.time.Times;

import java.time.Duration;
import java.util.UUID;

/**
 * One person a plugin might want to pick.
 *
 * <p>Plain data, and deliberately not an {@code OfflinePlayer}: every question worth asking about
 * this list — what order, who to leave out, how long ago somebody was here — is then ordinary code
 * that can be tested, and only the last step needs a server.
 *
 * @param id       their unique id, which is the only thing about them that never changes
 * @param name     the name last seen; people change these, which is exactly why the id is the key
 * @param online   whether they are here now
 * @param lastSeen when they were last here, in milliseconds; 0 for somebody never seen
 */
public record PlayerEntry(UUID id, String name, boolean online, long lastSeen) {

    public PlayerEntry {
        if (id == null) {
            throw new IllegalArgumentException("a player entry needs an id");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a player entry needs a name");
        }
        name = name.trim();
    }

    /**
     * How long ago they were here, the way somebody would say it.
     *
     * <p>"here now" for somebody standing in front of you, because "0 seconds ago" is nonsense, and
     * "never seen" rather than a date in 1970 for somebody the server has no record of.
     */
    public String lastSeenDescribed(long now) {
        if (online) {
            return "here now";
        }
        if (lastSeen <= 0) {
            return "never seen";
        }
        long since = now - lastSeen;
        return since < 1_000 ? "just now" : Times.describe(Duration.ofMillis(since)) + " ago";
    }

    /** How long ago they were here. Empty for somebody who is here, or was never seen. */
    public java.util.Optional<Duration> away(long now) {
        return online || lastSeen <= 0
                ? java.util.Optional.empty()
                : java.util.Optional.of(Duration.ofMillis(Math.max(0, now - lastSeen)));
    }
}

```

### src/main/java/de/raindancer/core/choose/PlayerDirectory.java
```java
package de.raindancer.core.ui.choose;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Everybody a plugin could pick, in an order that puts the right person near the top.
 *
 * <h2>Why picking a player is its own problem</h2>
 * Because "type their name" fails exactly when it matters. Somebody being banned, unbanned, or
 * having a claim transferred is usually <em>offline</em> — that is generally why it is being done
 * through a menu at all — and their name is the one thing nobody remembers correctly: a capital
 * letter, an underscore, a zero for an O. A list that only holds who is online is a list that cannot
 * do the job it exists for.
 *
 * <h2>The order</h2>
 * Online first, then whoever was here most recently. Alphabetical is the order that looks tidy and
 * helps nobody: on a server four years old it puts the person you want on page eleven.
 *
 * <h2>Why the people are injected</h2>
 * {@code Bukkit.getOfflinePlayers()} reads the whole player data directory and needs a server. The
 * ordering, the searching and the filtering are what go wrong, so they are on this side of that line.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread as far as this class goes. Whether the supplier is depends on the supplier —
 * the Bukkit one touches disk, so a caller should not be asking it every tick.
 */
public final class PlayerDirectory {

    /** Online first, then most recently seen, then by name so a tie does not shuffle about. */
    private static final Comparator<PlayerEntry> ORDER =
            Comparator.comparing(PlayerEntry::online).reversed()
                    .thenComparing(Comparator.comparingLong(PlayerEntry::lastSeen).reversed())
                    .thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT));

    /**
     * Where the line between "recently" and "long ago" falls by default.
     *
     * <p>A month: long enough that somebody who plays at weekends is still near the top, short enough
     * that a four-year-old server's thousands of one-visit names are not.
     */
    private static final Duration RECENTLY = Duration.ofDays(30);

    private final Supplier<List<PlayerEntry>> people;
    private final LongSupplier clock;
    private final Set<UUID> hidden;
    private final Duration recently;

    public PlayerDirectory(Supplier<List<PlayerEntry>> people, LongSupplier clock) {
        this(people, clock, Set.of(), RECENTLY);
    }

    private PlayerDirectory(Supplier<List<PlayerEntry>> people, LongSupplier clock,
                            Set<UUID> hidden, Duration recently) {
        this.people = people;
        this.clock = clock;
        this.hidden = hidden;
        this.recently = recently;
    }

    /**
     * The same directory with a different idea of what "recently" means.
     *
     * <p>What counts as recent on a server people play every evening is not what counts on one that
     * runs a season every summer.
     */
    public PlayerDirectory countingRecentAs(Duration recently) {
        return new PlayerDirectory(people, clock, hidden,
                recently == null || recently.isNegative() ? RECENTLY : recently);
    }

    /** How long ago somebody was here, as a rank. */
    public Presence presenceOf(PlayerEntry entry) {
        if (entry == null) {
            return Presence.LONG_AGO;
        }
        if (entry.online()) {
            return Presence.HERE;
        }
        if (entry.lastSeen() <= 0) {
            // A file with no recorded visit. Long ago rather than "here" — the alternative puts a
            // name nobody has ever seen at the top of the list.
            return Presence.LONG_AGO;
        }
        return clock.getAsLong() - entry.lastSeen() <= recently.toMillis()
                ? Presence.RECENTLY : Presence.LONG_AGO;
    }

    /**
     * Everybody, in sections, in the order a menu should show them.
     *
     * <p>Every rank is present even when empty is not — an empty section is a heading with nothing
     * under it — but nobody is ever dropped: the sum of the sections is the whole list.
     */
    public java.util.Map<Presence, List<PlayerEntry>> bySection() {
        java.util.Map<Presence, List<PlayerEntry>> sections =
                new java.util.LinkedHashMap<>();
        for (Presence presence : Presence.values()) {
            List<PlayerEntry> theirs = everybody().stream()
                    .filter(entry -> presenceOf(entry) == presence)
                    .toList();
            if (!theirs.isEmpty()) {
                sections.put(presence, theirs);
            }
        }
        return sections;
    }

    /** Everybody in one rank. */
    public List<PlayerEntry> inSection(Presence presence) {
        return bySection().getOrDefault(presence, List.of());
    }

    /** Everybody the server knows about, in the order above. */
    public List<PlayerEntry> everybody() {
        return people.get().stream()
                .filter(entry -> !hidden.contains(entry.id()))
                .sorted(ORDER)
                .toList();
    }

    /** Just the people who are here. */
    public List<PlayerEntry> online() {
        return everybody().stream().filter(PlayerEntry::online).toList();
    }

    /**
     * Everybody seen within this long, plus everybody online.
     *
     * <p>For a server old enough that the full list is thousands of names nobody is looking for.
     */
    public List<PlayerEntry> seenWithin(Duration recently) {
        if (recently == null) {
            return everybody();
        }
        long since = clock.getAsLong() - recently.toMillis();
        return everybody().stream()
                .filter(entry -> entry.online() || entry.lastSeen() >= since)
                .toList();
    }

    /**
     * Everybody whose name contains this, in any case; an exact match first.
     *
     * <p>The exact-match rule earns its keep with names like {@code Rain} and
     * {@code Raindancer118}: searching for the shorter one must not put the longer one above it.
     */
    public List<PlayerEntry> search(String text) {
        if (text == null || text.isBlank()) {
            return everybody();
        }
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        return everybody().stream()
                .filter(entry -> entry.name().toLowerCase(Locale.ROOT).contains(wanted))
                .sorted(Comparator.comparing((PlayerEntry entry) ->
                        entry.name().equalsIgnoreCase(wanted) ? 0 : 1))
                .toList();
    }

    /** One person by id. */
    public Optional<PlayerEntry> byId(UUID id) {
        return id == null ? Optional.empty()
                : everybody().stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    /** One person by exactly their name, in any case. */
    public Optional<PlayerEntry> byName(String name) {
        return name == null ? Optional.empty()
                : everybody().stream().filter(entry -> entry.name().equalsIgnoreCase(name.trim()))
                        .findFirst();
    }

    /**
     * The same directory without these people in it.
     *
     * <p>For leaving the person doing the picking out of their own list — a menu offering to ban
     * yourself is a menu with a bug in it.
     */
    public PlayerDirectory excluding(UUID... ids) {
        Set<UUID> without = new java.util.HashSet<>(hidden);
        for (UUID id : ids) {
            if (id != null) {
                without.add(id);
            }
        }
        return new PlayerDirectory(people, clock, Set.copyOf(without), recently);
    }

    public int size() {
        return everybody().size();
    }
}

```

### src/main/java/de/raindancer/core/choose/Presence.java
```java
package de.raindancer.core.ui.choose;

/**
 * How long ago somebody was here — the rank a list of players is sectioned by.
 *
 * <p>Ranks rather than a filter, because both of the obvious answers are wrong. Leaving the long-gone
 * out breaks the case a player chooser exists for: the person being unbanned or having their claim
 * transferred is usually the one nobody has seen for a year. Mixing them in breaks it too, because a
 * four-year-old server has thousands of them and they bury the six names anybody is looking for.
 */
public enum Presence {

    /** Here now. */
    HERE("Online", "LIME_DYE"),

    /** Not here, but recently enough that somebody would remember them. */
    RECENTLY("Seen Recently", "CLOCK"),

    /** A long time ago, or never. Still pickable, and clearly not in the way. */
    LONG_AGO("Long Ago", "COBWEB");

    private final String title;
    private final String icon;

    Presence(String title, String icon) {
        this.title = title;
        this.icon = icon;
    }

    /** What to write above the section. */
    public String title() {
        return title;
    }

    /** The material to draw the section with, by name. */
    public String icon() {
        return icon;
    }
}

```

### src/main/java/de/raindancer/core/vote/Ballot.java
```java
package de.raindancer.core.content.vote;

/**
 * What happened when somebody tried to vote.
 *
 * <p>Six answers rather than a boolean, because "your vote was not counted" gets asked again and
 * "the vote closed a minute ago" does not. Telling a player who changed their mind that they
 * "already voted" is the sort of small wrongness that makes people distrust the result.
 */
public enum Ballot {

    /** Counted. */
    COUNTED("Your vote has been counted."),

    /** They had voted for something else, and now they have not. */
    CHANGED("Your vote has been changed."),

    /** They voted for the same thing again. Nothing happened, and nothing was wrong. */
    ALREADY("You already voted for that."),

    /** There is no such vote, or there never was. */
    NO_SUCH_VOTE("There is no vote with that name."),

    /** The vote has finished. */
    CLOSED("That vote has already ended."),

    /** They are not one of the people being asked. */
    NOT_YOURS("You are not being asked in this vote."),

    /** That is not one of the answers. */
    NOT_AN_OPTION("That is not one of the answers.");

    private final String saying;

    Ballot(String saying) {
        this.saying = saying;
    }

    /** What to tell the player, in their words. */
    public String saying() {
        return saying;
    }

    /** Whether their answer is now on the record. */
    public boolean isCounted() {
        return this == COUNTED || this == CHANGED;
    }
}

```

### src/main/java/de/raindancer/core/vote/Tally.java
```java
package de.raindancer.core.content.vote;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How a vote stands, or how it ended.
 *
 * <p>A snapshot, not a view: a result being read while it is still being written is how two people
 * come away with two different numbers from the same vote.
 */
public final class Tally {

    private final String question;
    private final Map<String, Integer> counts;
    private final boolean finished;

    Tally(String question, Map<String, Integer> counts, boolean finished) {
        this.question = question;
        this.counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        this.finished = finished;
    }

    public String question() {
        return question;
    }

    /** Whether the vote has ended. A tally of a vote still running is a running total. */
    public boolean isFinished() {
        return finished;
    }

    /** Every answer and how many chose it, in the order they were on the ballot. */
    public Map<String, Integer> counts() {
        return counts;
    }

    /** How many chose one answer. */
    public int votesFor(String option) {
        if (option == null) {
            return 0;
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(option.trim()))
                .mapToInt(Map.Entry::getValue)
                .findFirst()
                .orElse(0);
    }

    /** How many people voted at all. */
    public int totalCast() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** What share of the votes cast one answer got, from 0 to 1. */
    public double shareOf(String option) {
        int total = totalCast();
        return total == 0 ? 0 : (double) votesFor(option) / total;
    }

    /** The answers with the most votes — more than one when it is a tie. */
    public List<String> leaders() {
        int most = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (most == 0) {
            return List.of();
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() == most)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * The winner, if there is one.
     *
     * <p>Empty for a tie and empty when nobody voted. Picking one out of a tie — by ballot order, by
     * who voted first, by anything — is how a vote turns into an argument, so it deliberately will
     * not.
     */
    public Optional<String> winner() {
        List<String> leaders = leaders();
        return leaders.size() == 1 ? Optional.of(leaders.getFirst()) : Optional.empty();
    }

    public boolean isTie() {
        return leaders().size() > 1;
    }

    /** The result in one line, for chat or a log. */
    public String describe() {
        if (totalCast() == 0) {
            return "nobody voted";
        }
        StringBuilder built = new StringBuilder();
        counts.forEach((option, count) -> {
            if (!built.isEmpty()) {
                built.append(", ");
            }
            built.append(option).append(": ").append(count)
                    .append(" (").append(Math.round(shareOf(option) * 100)).append("%)");
        });
        return built.toString();
    }

    static String normalise(String option) {
        return option == null ? "" : option.trim().toLowerCase(Locale.ROOT);
    }
}

```

### src/main/java/de/raindancer/core/vote/Vote.java
```java
package de.raindancer.core.content.vote;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One question, its answers, and who has said what.
 *
 * <h2>Why the answers are held here and not in a map somewhere</h2>
 * Because a vote is a thing with rules — one ballot per person, changeable until the deadline,
 * closed after it — and those rules only hold if there is one place they are enforced. A map of
 * player to answer plus a scheduled task is the version that lets somebody vote twice.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. A tally is a snapshot rather than a view, so nobody reads a count while it
 * is being written.
 */
public final class Vote {

    private final UUID id;
    private final UUID startedBy;
    private final String question;
    private final List<String> options;
    private final long openedAt;
    private final long closesAt;
    /** Null means everybody. A named set is a town council, a party, a staff vote. */
    private final Set<UUID> mayVote;

    private final Map<UUID, String> cast = new ConcurrentHashMap<>();
    private volatile boolean closed;

    Vote(UUID id, UUID startedBy, String question, List<String> options, long openedAt,
         long closesAt, Set<UUID> mayVote) {
        this.id = id;
        this.startedBy = startedBy;
        this.question = question;
        this.options = List.copyOf(options);
        this.openedAt = openedAt;
        this.closesAt = closesAt;
        this.mayVote = mayVote == null ? null : Set.copyOf(mayVote);
    }

    public UUID id() {
        return id;
    }

    public UUID startedBy() {
        return startedBy;
    }

    public String question() {
        return question;
    }

    /** The answers, as they were written, in the order they were given. */
    public List<String> options() {
        return options;
    }

    public long openedAt() {
        return openedAt;
    }

    public long closesAt() {
        return closesAt;
    }

    /** Whether it is still taking answers. */
    public boolean isOpen(long now) {
        return !closed && now < closesAt;
    }

    /** How long is left, for a bossbar or a countdown. Empty once it has ended. */
    public Optional<Duration> timeLeft(long now) {
        return isOpen(now) ? Optional.of(Duration.ofMillis(closesAt - now)) : Optional.empty();
    }

    /** Whether one person is being asked at all. */
    public boolean mayVote(UUID player) {
        return player != null && (mayVote == null || mayVote.contains(player));
    }

    /** Who is being asked, or empty for everybody. */
    public Optional<Set<UUID>> electorate() {
        return Optional.ofNullable(mayVote);
    }

    /** Whether this person has answered. Not <em>what</em> they answered — see the class comment. */
    public boolean hasVoted(UUID player) {
        return player != null && cast.containsKey(player);
    }

    /** How many have answered. */
    public int turnout() {
        return cast.size();
    }

    void close() {
        this.closed = true;
    }

    boolean isClosedEarly() {
        return closed;
    }

    /**
     * Records an answer.
     *
     * <p>Every rule about who may answer and when lives here rather than in the caller, because a
     * rule enforced in two places is a rule enforced in one and a half.
     */
    Ballot record(UUID player, String option, long now) {
        if (!isOpen(now)) {
            return Ballot.CLOSED;
        }
        if (!mayVote(player)) {
            return Ballot.NOT_YOURS;
        }
        String chosen = options.stream()
                .filter(candidate -> candidate.equalsIgnoreCase(option == null ? "" : option.trim()))
                .findFirst()
                .orElse(null);
        if (chosen == null) {
            return Ballot.NOT_AN_OPTION;
        }
        String before = cast.put(player, chosen);
        if (before == null) {
            return Ballot.COUNTED;
        }
        return before.equals(chosen) ? Ballot.ALREADY : Ballot.CHANGED;
    }

    /** How it stands, as a snapshot. */
    Tally tally(long now) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        options.forEach(option -> counts.put(option, 0));
        // Copied first: counting straight off the live map is how two people reading the same vote
        // come away with two different totals.
        for (String answer : new LinkedHashSet<>(cast.values()).isEmpty()
                ? List.<String>of() : List.copyOf(cast.values())) {
            counts.computeIfPresent(answer, (option, count) -> count + 1);
        }
        return new Tally(question, counts, !isOpen(now));
    }

    static String key(String option) {
        return option == null ? "" : option.trim().toLowerCase(Locale.ROOT);
    }
}

```

### src/main/java/de/raindancer/core/vote/Votes.java
```java
package de.raindancer.core.content.vote;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Every vote running on the server.
 *
 * <h2>Why Core owns this</h2>
 * Because the simplest useful version — an operator asks a question, everybody answers, the answer
 * with the most votes wins — is wanted by half the plugins on a server and is wrong in the same ways
 * every time it is rewritten: somebody votes twice, a changed vote is counted as two, the result is
 * read mid-write, or a tie quietly declares a winner. Each of those is a public argument rather than
 * a bug report.
 *
 * <p>It is also the thing the town council needs. "The council must approve a claim inside a town"
 * is a vote with a named electorate and a deadline, which is this class with a set passed in.
 *
 * <h2>Using it</h2>
 * <pre>{@code
 * Vote vote = core.votes().open(op, "Reset the farm world?", List.of("Yes", "No"),
 *         Times.parse("2min").orElseThrow()).orElseThrow();
 *
 * core.votes().cast(vote.id(), player, "Yes");     // answers with a Ballot saying what happened
 * core.votes().sweep().forEach(this::announce);    // on a timer; each id once, as it ends
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * Safe from any thread.
 */
public final class Votes {

    private static final LogChannel log = Log.of("votes");

    /** How long a finished vote's result is kept before it is let go. */
    private static final Duration KEEP_RESULTS = Duration.ofHours(24);

    private final LongSupplier clock;
    private final Map<UUID, Vote> votes = new ConcurrentHashMap<>();
    /** Which finished votes have already been reported, so a timer does not announce them twice. */
    private final Set<UUID> announced = ConcurrentHashMap.newKeySet();

    /** @param clock milliseconds; injected so deadlines can be tested without waiting for them */
    public Votes(LongSupplier clock) {
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------- starting

    /** A vote everybody may answer. */
    public Optional<Vote> open(UUID startedBy, String question, List<String> options,
                               Duration lasting) {
        return open(startedBy, question, options, lasting, null);
    }

    /**
     * A vote, optionally with a named electorate.
     *
     * <p>Refuses rather than corrects: a question with one answer, two answers spelled the same, or
     * no deadline are all mistakes worth stopping at the point they are made. A vote that never
     * closes in particular is one nobody ever acts on.
     *
     * @param mayVote who is being asked, or null for everybody
     * @return the vote, or empty when it was not a vote
     */
    public Optional<Vote> open(UUID startedBy, String question, List<String> options,
                               Duration lasting, Collection<UUID> mayVote) {
        if (question == null || question.isBlank()) {
            return refuse("a vote needs a question");
        }
        if (options == null || options.size() < 2) {
            return refuse("a vote needs at least two answers; one answer is an announcement");
        }
        if (lasting == null || lasting.isZero() || lasting.isNegative()) {
            return refuse("a vote needs a deadline, or nobody ever acts on it");
        }
        List<String> cleaned = options.stream()
                .filter(option -> option != null && !option.isBlank())
                .map(String::trim)
                .toList();
        if (cleaned.size() != options.size()) {
            return refuse("a vote cannot have a blank answer");
        }
        if (cleaned.stream().map(Vote::key).distinct().count() != cleaned.size()) {
            return refuse("two answers spelled the same split the vote for no reason");
        }

        long now = clock.getAsLong();
        Vote vote = new Vote(UUID.randomUUID(), startedBy, question.trim(), cleaned, now,
                now + lasting.toMillis(), mayVote == null ? null : Set.copyOf(mayVote));
        votes.put(vote.id(), vote);
        log.info("Vote opened: \"{}\" with {} answers, closing in {}", vote.question(),
                cleaned.size(), de.raindancer.core.world.time.Times.brief(lasting));
        return Optional.of(vote);
    }

    private Optional<Vote> refuse(String why) {
        log.warn("A vote was not started: {}", why);
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------- answering

    /** Records somebody's answer, and says what happened. */
    public Ballot cast(UUID voteId, UUID player, String option) {
        Vote vote = voteId == null ? null : votes.get(voteId);
        if (vote == null) {
            return Ballot.NO_SUCH_VOTE;
        }
        return vote.record(player, option, clock.getAsLong());
    }

    /** Whether somebody has answered. Not what they answered. */
    public boolean hasVoted(UUID voteId, UUID player) {
        Vote vote = voteId == null ? null : votes.get(voteId);
        return vote != null && vote.hasVoted(player);
    }

    // ---------------------------------------------------------------------------- looking

    public Optional<Vote> byId(UUID voteId) {
        return voteId == null ? Optional.empty() : Optional.ofNullable(votes.get(voteId));
    }

    /** Everything still taking answers. */
    public List<Vote> open() {
        long now = clock.getAsLong();
        return votes.values().stream().filter(vote -> vote.isOpen(now)).toList();
    }

    /** Everything one person is being asked and has not answered yet. */
    public List<Vote> waitingOn(UUID player) {
        long now = clock.getAsLong();
        return votes.values().stream()
                .filter(vote -> vote.isOpen(now))
                .filter(vote -> vote.mayVote(player))
                .filter(vote -> !vote.hasVoted(player))
                .toList();
    }

    /** How a vote stands, or how it ended. */
    public Optional<Tally> tally(UUID voteId) {
        return byId(voteId).map(vote -> vote.tally(clock.getAsLong()));
    }

    // ---------------------------------------------------------------------------- ending

    /**
     * Ends one early.
     *
     * @return whether this changed anything; false when it had already ended
     */
    public boolean close(UUID voteId) {
        Vote vote = voteId == null ? null : votes.get(voteId);
        if (vote == null || !vote.isOpen(clock.getAsLong())) {
            return false;
        }
        vote.close();
        return true;
    }

    /**
     * Finds votes whose time has run out, and forgets results nobody needs any more.
     *
     * <p>Called on a timer. Each finished vote comes back <em>once</em>: a timer that announced the
     * result every second until somebody restarted the server would be worse than no announcement.
     *
     * @return the votes that have just ended, in no particular order
     */
    public List<UUID> sweep() {
        long now = clock.getAsLong();
        List<UUID> justEnded = new ArrayList<>();
        for (Vote vote : List.copyOf(votes.values())) {
            if (!vote.isOpen(now) && announced.add(vote.id())) {
                justEnded.add(vote.id());
                log.info("Vote ended: \"{}\" — {}", vote.question(), vote.tally(now).describe());
            }
            // Kept for a day so the result outlives the vote and somebody can still act on it;
            // let go after that, because every vote ever held is a leak with a long fuse.
            if (!vote.isOpen(now) && now - vote.closesAt() > KEEP_RESULTS.toMillis()) {
                votes.remove(vote.id());
                announced.remove(vote.id());
            }
        }
        return justEnded;
    }

    /** How many votes are being remembered at all, running or finished. */
    public int size() {
        return votes.size();
    }
}

```

### src/main/java/de/raindancer/core/vanish/VanishSink.java
```java
package de.raindancer.core.moderation.vanish;

import java.util.UUID;

/**
 * The one thing in this package that talks to the server.
 *
 * <p>Who is hidden, who may see them, and what should happen when somebody vanishes is bookkeeping
 * and is tested without a server. Hiding an entity from every player is not.
 */
public interface VanishSink {

    /** Hides this player from everybody who may not see them. */
    void hide(UUID who);

    /** Shows them again. */
    void show(UUID who);

    /** Turns flight on or off. */
    void allowFlight(UUID who, boolean allowed);

    /** Whether other players bump into them. */
    void collidable(UUID who, boolean collides);

    /** Whether their joining and leaving is announced. */
    void silentJoinLeave(UUID who, boolean silent);
}

```

### src/main/java/de/raindancer/core/vanish/Vanish.java
```java
package de.raindancer.core.moderation.vanish;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Being properly not here.
 *
 * <h2>Why this is Core's</h2>
 * Because vanish is not one feature — it is a promise every other feature has to keep. Somebody
 * hidden who still appears in the tablist, still counts in "3 players online", or whose join message
 * went out anyway is not hidden, and each of those belongs to a different subsystem. Only the thing
 * that owns the tablist, the chat and the player list can make the promise hold.
 *
 * <p>The practical consequence for a plugin is one line: ask {@link #visibleOf} instead of
 * {@code Bukkit.getOnlinePlayers()}, and {@link #isVanished} before mentioning anybody. Nine plugins
 * each keeping their own set is nine chances for five of them to forget.
 *
 * <h2>Why the extras are optional</h2>
 * Flight, gamemode and the rest come apart in practice: somebody may want to be invisible without
 * flying, or to look at a build in creative without being hidden. Bundling them means the one you
 * did not want comes along too, and turning it off afterwards is what leaves a moderator stuck in
 * survival at bedrock. Flight is remembered as it was and put back exactly as it was found.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread.
 */
public final class Vanish {

    private static final LogChannel log = Log.of("vanish");

    private final VanishSink sink;

    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    private final Set<UUID> maySee = ConcurrentHashMap.newKeySet();
    /** Who could already fly before they vanished, so they are not grounded when they come back. */
    private final Set<UUID> couldAlreadyFly = ConcurrentHashMap.newKeySet();

    private volatile boolean flightWhileVanished = true;

    public Vanish(VanishSink sink) {
        this.sink = sink;
    }

    // ---------------------------------------------------------------------------- settings

    /**
     * Whether vanishing also grants flight.
     *
     * <p>On by default, because somebody who is invisible and walking is somebody whose footsteps
     * and door-opening give them away. Off for a server that would rather keep the two apart.
     */
    public void flightWhileVanished(boolean granted) {
        this.flightWhileVanished = granted;
    }

    public boolean isFlightWhileVanished() {
        return flightWhileVanished;
    }

    // ---------------------------------------------------------------------------- going

    /** Hides somebody. Answers whether this changed anything. */
    public boolean vanish(UUID who) {
        return vanish(who, false);
    }

    /**
     * Hides somebody, remembering whether they could already fly.
     *
     * @param couldFlyAlready whether flight was already theirs — a creative builder, or somebody
     *                        with the permission. Passing this stops {@link #reveal} taking away
     *                        something it never gave, which is how a builder lands in the void.
     */
    public boolean vanish(UUID who, boolean couldFlyAlready) {
        if (who == null || !hidden.add(who)) {
            // Already hidden. Re-hiding re-sends packets to every player on the server for nothing.
            return false;
        }
        if (couldFlyAlready) {
            couldAlreadyFly.add(who);
        }
        sink.hide(who);
        sink.collidable(who, false);
        sink.silentJoinLeave(who, true);
        if (flightWhileVanished && !couldFlyAlready) {
            sink.allowFlight(who, true);
        }
        log.info("{} vanished.", who);
        return true;
    }

    /** Brings somebody back. Answers whether they were hidden. */
    public boolean reveal(UUID who) {
        if (who == null || !hidden.remove(who)) {
            return false;
        }
        sink.show(who);
        sink.collidable(who, true);
        sink.silentJoinLeave(who, false);
        if (flightWhileVanished && !couldAlreadyFly.remove(who)) {
            // Only what was granted is taken back. A creative-mode builder who vanished must not
            // land in the void when they return.
            sink.allowFlight(who, false);
        }
        log.info("{} is visible again.", who);
        return true;
    }

    /** Hides or reveals. Answers whether they are now hidden. */
    public boolean toggle(UUID who) {
        return isVanished(who) ? !reveal(who) : vanish(who);
    }

    /** Brings everybody back — for a shutdown. Answers how many. */
    public int revealEverybody() {
        int count = 0;
        for (UUID who : List.copyOf(hidden)) {
            if (reveal(who)) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------------------- asking

    public boolean isVanished(UUID who) {
        return who != null && hidden.contains(who);
    }

    /** Everybody hidden. */
    public Set<UUID> everybodyVanished() {
        return Set.copyOf(hidden);
    }

    /**
     * The ones a plugin should treat as online.
     *
     * <p>The call to make instead of {@code getOnlinePlayers()}. Nearly every place vanish leaks is
     * a place that skipped it.
     */
    public List<UUID> visibleOf(Collection<UUID> players) {
        return players == null ? List.of()
                : players.stream().filter(who -> !isVanished(who)).toList();
    }

    /** How many of these count as online. */
    public int countOf(Collection<UUID> players) {
        return visibleOf(players).size();
    }

    /**
     * Whether one player can see another.
     *
     * <p>Somebody can always see themselves — a moderator who cannot has been made to disappear
     * rather than hidden — and anybody allowed to see hidden players can see all of them, so staff
     * do not spend the night walking into each other.
     */
    public boolean canSee(UUID viewer, UUID target) {
        if (viewer == null || target == null) {
            return false;
        }
        return viewer.equals(target) || !isVanished(target) || maySeeVanished(viewer);
    }

    /** Whether somebody is allowed to see hidden players. */
    public boolean maySeeVanished(UUID who) {
        return who != null && maySee.contains(who);
    }

    /** Says whether somebody may see hidden players — from a permission, usually, on join. */
    public void maySeeVanished(UUID who, boolean may) {
        if (who == null) {
            return;
        }
        if (may) {
            maySee.add(who);
        } else {
            maySee.remove(who);
        }
    }

    // ---------------------------------------------------------------------------- leaving

    /**
     * Forgets what was only true for this visit, keeping whether they are hidden.
     *
     * <p>Called when somebody leaves. Being hidden deliberately survives: a moderator who reconnects
     * and is suddenly visible has been given away by the plugin that was hiding them.
     */
    public void forgetSession(UUID who) {
        if (who != null) {
            maySee.remove(who);
        }
    }

    /** Forgets somebody entirely — for a player removed from the server. */
    public void forget(UUID who) {
        if (who != null) {
            hidden.remove(who);
            maySee.remove(who);
            couldAlreadyFly.remove(who);
        }
    }
}

```

### src/main/java/de/raindancer/core/vanish/BukkitVanishSink.java
```java
package de.raindancer.core.moderation.vanish;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * The handful of calls that actually make somebody invisible.
 *
 * <p>Everything about who is hidden, who may see them and what should be restored afterwards is on
 * the other side of {@link VanishSink} and is tested without a server.
 *
 * <p>Uses {@code hidePlayer} rather than an invisibility effect on purpose. An invisible player is
 * still in the tablist, still in the player list, still bumps into things and still shows their
 * armour — which is not hidden, it is translucent.
 */
public final class BukkitVanishSink implements VanishSink {

    private static final LogChannel log = Log.of("vanish");

    private final Plugin plugin;
    private final Vanish vanish;

    /** @param vanish asked who is allowed to see whom, so staff keep seeing each other */
    public BukkitVanishSink(Plugin plugin, Vanish vanish) {
        this.plugin = plugin;
        this.vanish = vanish;
    }

    @Override
    public void hide(UUID who) {
        Player target = Bukkit.getPlayer(who);
        if (target == null) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(target) && !vanish.maySeeVanished(viewer.getUniqueId())) {
                viewer.hidePlayer(plugin, target);
            }
        }
    }

    @Override
    public void show(UUID who) {
        Player target = Bukkit.getPlayer(who);
        if (target == null) {
            return;
        }
        // Shown to everybody, including those who could already see them: showPlayer on somebody
        // who was never hidden is harmless, and missing one leaves a player invisible to one person
        // for the rest of the session with nothing to explain it.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.showPlayer(plugin, target);
        }
    }

    @Override
    public void allowFlight(UUID who, boolean allowed) {
        Player target = Bukkit.getPlayer(who);
        if (target == null) {
            return;
        }
        target.setAllowFlight(allowed);
        if (!allowed && target.isFlying()) {
            // Set to not flying first, or the client and server disagree about where they are and
            // the player is rubber-banded back into the air.
            target.setFlying(false);
        }
    }

    @Override
    public void collidable(UUID who, boolean collides) {
        Player target = Bukkit.getPlayer(who);
        if (target != null) {
            target.setCollidable(collides);
        }
    }

    @Override
    public void silentJoinLeave(UUID who, boolean silent) {
        // Nothing to do to the server here: whether a message goes out is decided when the event
        // fires, by asking Vanish. Kept on the interface because that is where the decision belongs
        // and because a sink that persisted it would be a second place to get it wrong.
        log.info("{} will {} join and leave quietly.", who, silent ? "now" : "no longer");
    }
}

```

### src/main/java/de/raindancer/core/vanish/VanishListener.java
```java
package de.raindancer.core.moderation.vanish;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Keeping the promise across joins and leaves.
 *
 * <p>The two moments vanish usually breaks. Somebody who joins has to be hidden from the person who
 * just arrived — the new player has never been told to hide them — and somebody hidden must not have
 * their arrival announced. Both are one line and both are always forgotten.
 */
public final class VanishListener implements Listener {

    private final Plugin plugin;
    private final Vanish vanish;
    private final String seeVanishedPermission;

    public VanishListener(Plugin plugin, Vanish vanish, String seeVanishedPermission) {
        this.plugin = plugin;
        this.vanish = vanish;
        this.seeVanishedPermission = seeVanishedPermission;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        vanish.maySeeVanished(joining.getUniqueId(),
                seeVanishedPermission != null && joining.hasPermission(seeVanishedPermission));

        if (vanish.isVanished(joining.getUniqueId())) {
            // Quietly: their own arrival must not be announced, and they have to be hidden again
            // from everybody, since a fresh connection knows nothing about who was hidden.
            event.joinMessage(null);
        }
        // Everybody already hidden has to be hidden from the person who just arrived. Without this
        // the newest player is the one person who can see every vanished moderator on the server.
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(joining) && vanish.isVanished(other.getUniqueId())
                    && !vanish.maySeeVanished(joining.getUniqueId())) {
                joining.hidePlayer(plugin, other);
            }
        }
        if (vanish.isVanished(joining.getUniqueId())) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(joining) && !vanish.maySeeVanished(viewer.getUniqueId())) {
                    viewer.hidePlayer(plugin, joining);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        if (vanish.isVanished(event.getPlayer().getUniqueId())) {
            event.quitMessage(null);
        }
        // Whether they may see hidden players is a fact about this session, not about them. Being
        // hidden is not, and deliberately survives — see Vanish#forgetSession.
        vanish.forgetSession(event.getPlayer().getUniqueId());
    }
}

```

### src/main/java/de/raindancer/core/player/Outcome.java
```java
package de.raindancer.core.moderation.players;

/**
 * What happened when a management action was tried.
 *
 * <p>Answers rather than a boolean, because a moderator clicking a button needs to know which of
 * "done", "they logged out", "that would have killed them" and "there was nothing to do" happened.
 * A silent false gets clicked again.
 */
public enum Outcome {

    /** It happened. */
    DONE("Done."),

    /** It was already the case. Nothing was wrong and nothing changed. */
    NOTHING_TO_DO("There was nothing to do."),

    /** They are not on the server. */
    NOT_ONLINE("They are not online."),

    /** The action would have killed them, and it was not a kill. */
    WOULD_KILL("That would have killed them; use kill if you meant to."),

    /** A number outside what the game accepts. */
    OUT_OF_RANGE("That is outside what the game allows."),

    /** A name that is not a thing — a gamemode, an effect. */
    NOT_UNDERSTOOD("That is not something this server knows about.");

    private final String saying;

    Outcome(String saying) {
        this.saying = saying;
    }

    /** What to tell whoever pressed the button. */
    public String saying() {
        return saying;
    }

    public boolean isDone() {
        return this == DONE;
    }
}

```

### src/main/java/de/raindancer/core/player/PlayerState.java
```java
package de.raindancer.core.moderation.players;

/**
 * A snapshot of somebody, as far as management cares.
 *
 * <p>Read once at the start of an action rather than asked repeatedly: every rule in
 * {@link PlayerAdmin} is about comparing what is to what is being asked for, and a value that
 * changes halfway through is a rule that decides on two different players.
 *
 * @param health    how much they have
 * @param maxHealth how much they can have — not always twenty
 * @param food      0 to 20
 * @param flying    whether flight is allowed them
 * @param gamemode  the name of their gamemode
 */
public record PlayerState(double health, double maxHealth, int food, boolean flying,
                          String gamemode) {

    public boolean isFull() {
        return health >= maxHealth;
    }

    public boolean isFed() {
        return food >= 20;
    }
}

```

### src/main/java/de/raindancer/core/player/PlayerAdminSink.java
```java
package de.raindancer.core.moderation.players;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The one thing in this package that touches the server.
 *
 * <p>Every rule about what is allowed, what would kill somebody and what is already the case lives
 * on the other side of this and is tested without a server. Everything here is one call.
 */
public interface PlayerAdminSink {

    /** How somebody is, or empty when they are not online. */
    Optional<PlayerState> stateOf(UUID who);

    void health(UUID who, double health);

    void food(UUID who, int food);

    /** @param lasting null for an effect that does not expire */
    void effect(UUID who, String effect, int level, Duration lasting);

    void clearEffect(UUID who, String effect);

    void clearAllEffects(UUID who);

    void allowFlight(UUID who, boolean allowed);

    void gamemode(UUID who, String mode);

    void kick(UUID who, String reason);

    void extinguish(UUID who);
}

```

### src/main/java/de/raindancer/core/player/PlayerAdmin.java
```java
package de.raindancer.core.moderation.players;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Doing things to a player, from a management screen.
 *
 * <h2>Why this is not a wrapper round {@code setHealth}</h2>
 * Because the mistakes are all in the edges and every plugin makes them again. Healing above the
 * maximum throws. Damaging for more than somebody has kills them, from a button labelled "damage".
 * Setting a speed effect without clearing the last one stacks them. Feeding past twenty throws.
 * Acting on somebody who logged out a second ago throws. Each of those is an exception in front of a
 * moderator, or a dead player who was meant to be nudged.
 *
 * <p>So every action answers an {@link Outcome} — done, nothing to do, they are gone, that would
 * have killed them — and none of them throws.
 *
 * <h2>What is here and what is not</h2>
 * The things a management screen does to somebody who is <em>present</em>: health, food, effects,
 * flight, gamemode, fire, kicking. Banning and muting are deliberately not here: they are records
 * that outlive a session and belong to {@code moderation.Punishments}, which already keeps a history
 * and enforces them. A management screen calls both.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread as far as this goes; the sink decides where its own work happens.
 */
public final class PlayerAdmin {

    private static final LogChannel log = Log.of("players");

    /** The highest amplifier the protocol carries. Beyond this the client sees nothing. */
    private static final int MAX_LEVEL = 255;

    /** The gamemodes there are. A name outside this is a typo, not a mode. */
    private static final Set<String> GAMEMODES =
            Set.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR");

    private final PlayerAdminSink sink;

    public PlayerAdmin(PlayerAdminSink sink) {
        this.sink = sink;
    }

    // ---------------------------------------------------------------------------- health

    /** Fills somebody up. */
    public Outcome heal(UUID who) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (state.get().isFull()) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.health(who, state.get().maxHealth());
        return Outcome.DONE;
    }

    /** Heals by an amount, stopping at their maximum rather than throwing past it. */
    public Outcome heal(UUID who, double amount) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (amount <= 0) {
            return Outcome.NOTHING_TO_DO;
        }
        if (state.get().isFull()) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.health(who, Math.min(state.get().maxHealth(), state.get().health() + amount));
        return Outcome.DONE;
    }

    /**
     * Takes health away — unless it would kill them.
     *
     * <p>A button labelled "damage" that kills somebody is a button that lied, so this refuses and
     * says so. {@link #kill} is how you mean it.
     */
    public Outcome damage(UUID who, double amount) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (amount <= 0) {
            return Outcome.NOTHING_TO_DO;
        }
        if (amount >= state.get().health()) {
            return Outcome.WOULD_KILL;
        }
        sink.health(who, state.get().health() - amount);
        return Outcome.DONE;
    }

    /** Kills them, deliberately. */
    public Outcome kill(UUID who) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        sink.health(who, 0);
        log.info("{} was killed from a management screen.", who);
        return Outcome.DONE;
    }

    // ---------------------------------------------------------------------------- food

    /** Fills their food bar. */
    public Outcome feed(UUID who) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (state.get().isFed()) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.food(who, 20);
        return Outcome.DONE;
    }

    /** Empties it. */
    public Outcome starve(UUID who) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (state.get().food() <= 0) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.food(who, 0);
        return Outcome.DONE;
    }

    /** Sets it to something in between. */
    public Outcome food(UUID who, int level) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (level < 0 || level > 20) {
            return Outcome.OUT_OF_RANGE;
        }
        sink.food(who, level);
        return Outcome.DONE;
    }

    // ---------------------------------------------------------------------------- effects

    /** Faster. Level 0 takes it off. */
    public Outcome speed(UUID who, int level, Duration lasting) {
        return give(who, "SPEED", level, lasting);
    }

    /** Slower. Level 0 takes it off. */
    public Outcome slowness(UUID who, int level, Duration lasting) {
        return give(who, "SLOWNESS", level, lasting);
    }

    /** Stronger. */
    public Outcome strength(UUID who, int level, Duration lasting) {
        return give(who, "STRENGTH", level, lasting);
    }

    /** Able to see in the dark. */
    public Outcome nightVision(UUID who, Duration lasting) {
        return give(who, "NIGHT_VISION", 1, lasting);
    }

    /** Unable to be hurt by much. */
    public Outcome resistance(UUID who, int level, Duration lasting) {
        return give(who, "RESISTANCE", level, lasting);
    }

    /**
     * Any effect at all.
     *
     * <p>Two rules that every hand-rolled version gets wrong. A level of zero <em>removes</em> the
     * effect rather than applying amplifier zero — which is level one, so an "off" button that
     * speeds somebody up. And an effect already on is cleared first rather than stacked, because two
     * speeds at once leaves a player moving at a speed nobody chose.
     *
     * @param level   1 upwards; 0 takes it away
     * @param lasting null for one that does not expire
     */
    public Outcome give(UUID who, String effect, int level, Duration lasting) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (effect == null || effect.isBlank()) {
            return Outcome.NOT_UNDERSTOOD;
        }
        String name = effect.trim().toUpperCase(Locale.ROOT);
        if (level < 0 || level > MAX_LEVEL) {
            return Outcome.OUT_OF_RANGE;
        }
        if (level == 0) {
            sink.clearEffect(who, name);
            return Outcome.DONE;
        }
        // Cleared first, always. Applying over an existing one is version-dependent — sometimes the
        // stronger wins, sometimes the newer — and "sometimes" is not something to build a menu on.
        sink.clearEffect(who, name);
        sink.effect(who, name, level, lasting);
        return Outcome.DONE;
    }

    /** Takes one effect away. */
    public Outcome take(UUID who, String effect) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (effect == null || effect.isBlank()) {
            return Outcome.NOT_UNDERSTOOD;
        }
        sink.clearEffect(who, effect.trim().toUpperCase(Locale.ROOT));
        return Outcome.DONE;
    }

    /** Takes everything off — the milk-bucket button. */
    public Outcome cure(UUID who) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        sink.clearAllEffects(who);
        return Outcome.DONE;
    }

    // ---------------------------------------------------------------------------- the rest

    /** Whether they may fly. */
    public Outcome flight(UUID who, boolean allowed) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (state.get().flying() == allowed) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.allowFlight(who, allowed);
        return Outcome.DONE;
    }

    /** Turns flight on if it is off, and off if it is on. */
    public Outcome toggleFlight(UUID who) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        return flight(who, !state.get().flying());
    }

    /** Their gamemode, by name, in any case. */
    public Outcome gamemode(UUID who, String mode) {
        Optional<PlayerState> state = stateOf(who);
        if (state.isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        if (mode == null || mode.isBlank()) {
            return Outcome.NOT_UNDERSTOOD;
        }
        String wanted = mode.trim().toUpperCase(Locale.ROOT);
        if (!GAMEMODES.contains(wanted)) {
            return Outcome.NOT_UNDERSTOOD;
        }
        if (wanted.equals(state.get().gamemode())) {
            return Outcome.NOTHING_TO_DO;
        }
        sink.gamemode(who, wanted);
        return Outcome.DONE;
    }

    /** The gamemodes there are, for a menu that offers them. */
    public static List<String> gamemodes() {
        return List.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR");
    }

    /**
     * Disconnects somebody, with a reason.
     *
     * <p>A blank reason becomes a real sentence: a player staring at an empty disconnect screen has
     * been told nothing, and will ask anyway.
     */
    public Outcome kick(UUID who, String reason) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        String said = reason == null || reason.isBlank()
                ? "You were disconnected by a moderator." : reason.trim();
        sink.kick(who, said);
        log.info("{} was kicked: {}", who, said);
        return Outcome.DONE;
    }

    /** Puts them out. */
    public Outcome extinguish(UUID who) {
        if (stateOf(who).isEmpty()) {
            return Outcome.NOT_ONLINE;
        }
        sink.extinguish(who);
        return Outcome.DONE;
    }

    /** How somebody is, for a screen that wants to draw it. */
    public Optional<PlayerState> stateOf(UUID who) {
        return who == null ? Optional.empty() : sink.stateOf(who);
    }
}

```

### src/main/java/de/raindancer/core/player/BukkitPlayerAdminSink.java
```java
package de.raindancer.core.moderation.players;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one-line half of player management.
 *
 * <p>Every rule about what is allowed, what would kill somebody and what is already the case lives
 * in {@link PlayerAdmin} and is tested without a server. This is the seam.
 */
public final class BukkitPlayerAdminSink implements PlayerAdminSink {

    private static final LogChannel log = Log.of("players");
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Effect names that turned out not to exist. Complained about once each. */
    private final Set<String> unknown = ConcurrentHashMap.newKeySet();

    @Override
    public Optional<PlayerState> stateOf(UUID who) {
        Player player = Bukkit.getPlayer(who);
        if (player == null) {
            return Optional.empty();
        }
        // The attribute rather than a hardcoded twenty: a server with a plugin that raises maximum
        // health has players for whom twenty is not full, and a heal button that stops there is a
        // heal button that does not heal.
        double max = player.getAttribute(Attribute.MAX_HEALTH) == null
                ? 20 : player.getAttribute(Attribute.MAX_HEALTH).getValue();
        return Optional.of(new PlayerState(player.getHealth(), max, player.getFoodLevel(),
                player.getAllowFlight(), player.getGameMode().name()));
    }

    @Override
    public void health(UUID who, double health) {
        Player player = Bukkit.getPlayer(who);
        if (player != null) {
            double max = player.getAttribute(Attribute.MAX_HEALTH) == null
                    ? 20 : player.getAttribute(Attribute.MAX_HEALTH).getValue();
            // Clamped here as well as decided above: setHealth outside the range throws, and this
            // is the last place before it that can stop that.
            player.setHealth(Math.clamp(health, 0, max));
        }
    }

    @Override
    public void food(UUID who, int food) {
        Player player = Bukkit.getPlayer(who);
        if (player != null) {
            player.setFoodLevel(Math.clamp(food, 0, 20));
        }
    }

    @Override
    public void effect(UUID who, String effect, int level, Duration lasting) {
        Player player = Bukkit.getPlayer(who);
        PotionEffectType type = effectType(effect);
        if (player == null || type == null) {
            return;
        }
        // Amplifier is level - 1: amplifier 0 is level I. Getting this backwards is what makes a
        // "Speed II" button apply Speed III, and it is the most common bug in this whole area.
        int amplifier = Math.max(0, level - 1);
        int ticks = lasting == null
                ? PotionEffect.INFINITE_DURATION : (int) Math.min(Integer.MAX_VALUE,
                        lasting.toMillis() / 50);
        player.addPotionEffect(new PotionEffect(type, ticks, amplifier, false, false, true));
    }

    @Override
    public void clearEffect(UUID who, String effect) {
        Player player = Bukkit.getPlayer(who);
        PotionEffectType type = effectType(effect);
        if (player != null && type != null) {
            player.removePotionEffect(type);
        }
    }

    @Override
    public void clearAllEffects(UUID who) {
        Player player = Bukkit.getPlayer(who);
        if (player != null) {
            player.getActivePotionEffects()
                    .forEach(active -> player.removePotionEffect(active.getType()));
        }
    }

    @Override
    public void allowFlight(UUID who, boolean allowed) {
        Player player = Bukkit.getPlayer(who);
        if (player == null) {
            return;
        }
        player.setAllowFlight(allowed);
        if (!allowed && player.isFlying()) {
            player.setFlying(false);
        }
    }

    @Override
    public void gamemode(UUID who, String mode) {
        Player player = Bukkit.getPlayer(who);
        if (player == null) {
            return;
        }
        try {
            player.setGameMode(GameMode.valueOf(mode));
        } catch (IllegalArgumentException notAMode) {
            log.warn("There is no gamemode called '{}'.", mode);
        }
    }

    @Override
    public void kick(UUID who, String reason) {
        Player player = Bukkit.getPlayer(who);
        if (player != null) {
            Component said = MINI.deserialize("<red>" + MINI.escapeTags(reason));
            player.kick(said);
        }
    }

    @Override
    public void extinguish(UUID who) {
        Player player = Bukkit.getPlayer(who);
        if (player != null) {
            player.setFireTicks(0);
        }
    }

    /** An effect by name, from the registry so a new one works without this class changing. */
    private PotionEffectType effectType(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim().toLowerCase(java.util.Locale.ROOT);
        PotionEffectType found = Registry.EFFECT.get(NamespacedKey.minecraft(key));
        if (found == null && unknown.add(key)) {
            log.warn("This server has no effect called '{}'.", key);
        }
        return found;
    }
}

```

### src/main/java/de/raindancer/core/invsee/Section.java
```java
package de.raindancer.core.moderation.invsee;

/**
 * The parts of what somebody is carrying.
 *
 * <h2>Why these are named rather than numbered</h2>
 * Because a player's inventory is one array with four different meanings in it, and every plugin
 * that treats it as one array gets something wrong. Slots 0–8 are the hotbar but they are stored
 * <em>after</em> the main storage; slots 36–39 are armour and are in the reverse of the order they
 * are worn; slot 40 is the off-hand. Nothing about that is guessable, so it is written down once
 * here and never again.
 *
 * <p>Naming them is also what makes a window worth looking at. "Their inventory" as thirty-six
 * identical squares tells a moderator nothing; hotbar, backpack, what they are wearing and what is
 * in their ender chest, laid out apart, is a page you can read at a glance.
 */
public enum Section {

    /** The nine slots they can hold. What somebody is about to use. */
    HOTBAR("Hotbar", "GOLDEN_SWORD", 9),

    /** The twenty-seven above it — the backpack. */
    STORAGE("Backpack", "CHEST", 27),

    /** Helmet, chestplate, leggings, boots. */
    ARMOUR("Worn", "IRON_CHESTPLATE", 4),

    /** The one in the other hand. */
    OFF_HAND("Off Hand", "SHIELD", 1),

    /** The ender chest, which is not in the inventory at all and is usually forgotten. */
    ENDER_CHEST("Ender Chest", "ENDER_CHEST", 27);

    private final String title;
    private final String icon;
    private final int size;

    Section(String title, String icon, int size) {
        this.title = title;
        this.icon = icon;
        this.size = size;
    }

    /** What to write above it. */
    public String title() {
        return title;
    }

    /** The material to label it with, by name. */
    public String icon() {
        return icon;
    }

    /** How many slots it has. */
    public int size() {
        return size;
    }

    /** Whether this is something worn, which is protected by default — see {@code Access}. */
    public boolean isEquipment() {
        return this == ARMOUR || this == OFF_HAND;
    }

    /** Whether this lives outside the inventory array and has to be read separately. */
    public boolean isSeparate() {
        return this == ENDER_CHEST;
    }
}

```

### src/main/java/de/raindancer/core/invsee/Slots.java
```java
package de.raindancer.core.moderation.invsee;

import java.util.Optional;

/**
 * Which part of an inventory a raw slot number belongs to, and where to draw it.
 *
 * <h2>Why this is a class and not four constants</h2>
 * Because the layout of a player's inventory is genuinely surprising and nothing about it is
 * guessable: the hotbar is slots 0–8 but is drawn at the <em>bottom</em>, storage is 9–35 and is
 * drawn above it, armour is 36–39 in the order boots, leggings, chestplate, helmet — the reverse of
 * how anybody would list them — and the off-hand is 40 on its own.
 *
 * <p>Every plugin that has ever shown somebody's inventory has got at least one of those wrong, and
 * the symptom is a moderator unequipping a helmet by clicking what looked like an empty slot.
 */
public final class Slots {

    /** Where each part starts in a player's inventory array. */
    public static final int HOTBAR_FIRST = 0;
    public static final int STORAGE_FIRST = 9;
    public static final int ARMOUR_FIRST = 36;
    public static final int OFF_HAND = 40;

    private Slots() {
    }

    /** Which part a raw inventory slot belongs to. */
    public static Optional<Section> sectionOf(int rawSlot) {
        if (rawSlot >= HOTBAR_FIRST && rawSlot < STORAGE_FIRST) {
            return Optional.of(Section.HOTBAR);
        }
        if (rawSlot >= STORAGE_FIRST && rawSlot < ARMOUR_FIRST) {
            return Optional.of(Section.STORAGE);
        }
        if (rawSlot >= ARMOUR_FIRST && rawSlot < OFF_HAND) {
            return Optional.of(Section.ARMOUR);
        }
        if (rawSlot == OFF_HAND) {
            return Optional.of(Section.OFF_HAND);
        }
        return Optional.empty();
    }

    /** Where a slot sits within its own part — the third hotbar slot, the second armour piece. */
    public static int indexWithin(int rawSlot) {
        return sectionOf(rawSlot).map(section -> switch (section) {
            case HOTBAR -> rawSlot - HOTBAR_FIRST;
            case STORAGE -> rawSlot - STORAGE_FIRST;
            case ARMOUR -> armourIndex(rawSlot);
            case OFF_HAND -> 0;
            case ENDER_CHEST -> rawSlot;
        }).orElse(-1);
    }

    /**
     * Armour, in the order a person would list it: helmet, chestplate, leggings, boots.
     *
     * <p>The array is the other way round — 36 is the boots — which is the single most reliable way
     * to draw somebody's armour upside down.
     */
    public static int armourIndex(int rawSlot) {
        return 3 - (rawSlot - ARMOUR_FIRST);
    }

    /** The raw slot of one armour piece, counting from the helmet. */
    public static int armourSlot(int fromHelmet) {
        return ARMOUR_FIRST + (3 - fromHelmet);
    }

    /** The raw slot of one place within a part. */
    public static int rawSlot(Section section, int indexWithin) {
        return switch (section) {
            case HOTBAR -> HOTBAR_FIRST + indexWithin;
            case STORAGE -> STORAGE_FIRST + indexWithin;
            case ARMOUR -> armourSlot(indexWithin);
            case OFF_HAND -> OFF_HAND;
            case ENDER_CHEST -> indexWithin;
        };
    }
}

```

### src/main/java/de/raindancer/core/invsee/Access.java
```java
package de.raindancer.core.moderation.invsee;

/**
 * What somebody watching an inventory is allowed to do to it.
 *
 * <p>Three levels rather than a boolean, because the middle one is the one people actually want:
 * being able to take a stolen item out of somebody's backpack without being able to unequip their
 * armour by clicking one slot too far.
 */
public enum Access {

    /** Look, and nothing else. */
    READ_ONLY("Looking"),

    /** Change what they are carrying, but not what they are wearing. */
    EDIT("Editing"),

    /** Change everything, armour and off-hand included. */
    EDIT_EVERYTHING("Editing everything");

    private final String saying;

    Access(String saying) {
        this.saying = saying;
    }

    public String saying() {
        return saying;
    }

    public boolean canEdit() {
        return this != READ_ONLY;
    }

    /** Whether this level may touch a given part. */
    public boolean mayChange(Section section) {
        if (this == READ_ONLY || section == null) {
            return false;
        }
        return this == EDIT_EVERYTHING || !section.isEquipment();
    }
}

```

### src/main/java/de/raindancer/core/invsee/InventoryViews.java
```java
package de.raindancer.core.moderation.invsee;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Who is looking inside whose inventory, and what they may do there.
 *
 * <h2>Why the rules are the whole feature</h2>
 * Opening somebody else's inventory is three lines. Everything that makes it safe is around it, and
 * every rule here exists because the version without it has broken a real server:
 *
 * <ul>
 *   <li>Two moderators editing the same inventory at once <b>duplicates items</b>, every time. Only
 *       one editor is allowed; anybody else may watch.</li>
 *   <li>A window left open after its owner logs out writes changes to nobody, or loses them. The
 *       owner leaving closes every window onto them.</li>
 *   <li>An editor who logs out while holding the lock would keep it until a restart. Leaving
 *       releases it.</li>
 *   <li>Armour and the off-hand are protected unless somebody deliberately asks for them, because
 *       unequipping a player mid-fight by clicking one slot too far is not an edit anybody meant.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. The editor lock is taken atomically, so two moderators clicking at the same
 * instant cannot both be told yes.
 */
public final class InventoryViews {

    private static final LogChannel log = Log.of("invsee");

    /** Who each watcher is watching. */
    private final Map<UUID, UUID> watching = new ConcurrentHashMap<>();
    /** What each watcher may do. */
    private final Map<UUID, Access> access = new ConcurrentHashMap<>();
    /** Who holds the one editing lock on each inventory. */
    private final Map<UUID, UUID> editors = new ConcurrentHashMap<>();

    /** Told to shut a watcher's window — the server's job, and the only thing here that is. */
    private final Consumer<String> closeWindow;

    public InventoryViews(Consumer<String> closeWindow) {
        this.closeWindow = closeWindow;
    }

    // ---------------------------------------------------------------------------- opening

    /**
     * Starts watching somebody.
     *
     * @return whether it was allowed; false when somebody else is already editing, or when a player
     *         tried to watch themselves
     */
    public boolean open(UUID watcher, UUID owner, Access level) {
        if (watcher == null || owner == null || watcher.equals(owner)) {
            // Your own inventory is a key rather than a menu, and the two behave differently enough
            // that pretending otherwise causes items to vanish.
            return false;
        }
        Access wanted = level == null ? Access.READ_ONLY : level;

        if (wanted.canEdit()) {
            // Taken atomically. Two moderators clicking at the same instant must not both be told
            // yes — that is the case that duplicates items.
            UUID already = editors.putIfAbsent(owner, watcher);
            if (already != null && !already.equals(watcher)) {
                return false;
            }
        }
        // Whatever they had open before is gone: one screen, one inventory. A window nobody is
        // looking at that still takes clicks is a window that will be clicked.
        release(watcher);
        watching.put(watcher, owner);
        access.put(watcher, wanted);
        return true;
    }

    // ---------------------------------------------------------------------------- asking

    /** Whose inventory somebody has open. */
    public Optional<UUID> watching(UUID watcher) {
        return watcher == null ? Optional.empty() : Optional.ofNullable(watching.get(watcher));
    }

    /** Everybody looking at one inventory. */
    public Set<UUID> watchersOf(UUID owner) {
        if (owner == null) {
            return Set.of();
        }
        return watching.entrySet().stream()
                .filter(entry -> entry.getValue().equals(owner))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Who, if anybody, is editing one inventory — so a watcher can be told why they cannot. */
    public Optional<UUID> editorOf(UUID owner) {
        return owner == null ? Optional.empty() : Optional.ofNullable(editors.get(owner));
    }

    /** What one watcher is allowed to do. */
    public Optional<Access> accessOf(UUID watcher) {
        return watcher == null ? Optional.empty() : Optional.ofNullable(access.get(watcher));
    }

    /** Whether a watcher may change something in one part of the inventory. */
    public boolean mayChange(UUID watcher, Section section) {
        return accessOf(watcher).map(level -> level.mayChange(section)).orElse(false);
    }

    /** Whether a watcher may change a raw inventory slot — for a click handler. */
    public boolean mayChangeSlot(UUID watcher, int rawSlot) {
        return Slots.sectionOf(rawSlot).map(section -> mayChange(watcher, section)).orElse(false);
    }

    public int size() {
        return watching.size();
    }

    // ---------------------------------------------------------------------------- closing

    /** Stops one watcher watching. Answers whether they were. */
    public boolean close(UUID watcher) {
        if (watcher == null || !watching.containsKey(watcher)) {
            return false;
        }
        release(watcher);
        return true;
    }

    /**
     * The owner has gone: every window onto them is closed.
     *
     * <p>A window onto somebody who has logged out is a window whose changes are written to nobody.
     *
     * @return who was watching, so they can be told why their screen shut
     */
    public Set<UUID> ownerLeft(UUID owner) {
        Set<UUID> theirWatchers = watchersOf(owner);
        for (UUID watcher : theirWatchers) {
            release(watcher);
            closeWindow.accept(watcher.toString());
        }
        editors.remove(owner);
        return theirWatchers;
    }

    /**
     * A watcher has gone.
     *
     * <p>The important half is the lock: an editor who logs out still holding it would stop anybody
     * editing that inventory until the server restarted.
     */
    public void watcherLeft(UUID watcher) {
        release(watcher);
    }

    /** Closes everything — for a shutdown. Answers how many. */
    public int closeEverything() {
        List<UUID> everybody = List.copyOf(watching.keySet());
        everybody.forEach(watcher -> {
            release(watcher);
            closeWindow.accept(watcher.toString());
        });
        return everybody.size();
    }

    /** Lets go of whatever one watcher held. */
    private void release(UUID watcher) {
        UUID owner = watching.remove(watcher);
        access.remove(watcher);
        if (owner != null) {
            // Only if it is theirs. Removing blindly would let a watcher release somebody else's
            // lock by closing their own read-only window.
            editors.remove(owner, watcher);
        }
    }
}

```

### src/main/java/de/raindancer/core/command/CoreCommands.java
```java
package de.raindancer.core.platform.command;

import de.raindancer.core.ui.chat.ClickCommand;
import de.raindancer.core.data.settings.SettingsCommand;
import de.raindancer.core.world.warp.WarpCommand;
import de.raindancer.core.world.farm.FarmWorldCommand;
import io.papermc.paper.command.brigadier.Commands;

import java.util.List;

/**
 * Ready-made commands for the things Core knows about — none of which Core registers.
 *
 * <h2>Why Core registers nothing</h2>
 * Because a library that takes {@code /warp} for itself has decided something that is not its to
 * decide. A server may already have a warp plugin, may want the settings behind a different name,
 * or may want none of them. Core's job is to make writing those commands a line rather than a
 * weekend; owning the names is a different job, and one nobody asked it to do.
 *
 * <p>So the handlers live here as building blocks, and a plugin registers whichever it wants:
 *
 * <pre>{@code
 * public final class MyBootstrap implements PluginBootstrap {
 *     public void bootstrap(BootstrapContext context) {
 *         context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
 *             CoreCommands.clickCallback(event.registrar());   // see below — buttons need this
 *             CoreCommands.settings(event.registrar(), "settings");
 *             CoreCommands.warps(event.registrar(), "warp");
 *         });
 *     }
 * }
 * }</pre>
 *
 * <h2>The one that is not really a command</h2>
 * {@link #clickCallback} is different in kind. A clickable thing in chat can only do one of three
 * things — open a URL, put text in the box, or run a command — so a button with a server-side
 * callback is a command by necessity, not by choice. Nobody types it and it takes an opaque token.
 * Without it registered somewhere, {@code buttons()} still produces readable text but nothing is
 * clickable, and Core says so once rather than leaving you wondering.
 *
 * <h2>Register these in a bootstrapper, not in onEnable</h2>
 * Paper fires the {@code COMMANDS} lifecycle event during the bootstrap phase. A handler registered
 * in {@code onEnable} is registered after that has already happened, so it never runs — with no
 * warning, no exception, and no line in the log. The command simply does not exist and
 * {@code dispatchCommand} answers false as though nobody had ever heard of it.
 *
 * <p>That is not a theoretical footnote. Core itself was written that way, and every chat button in
 * the library was dead on a real server for weeks: the callback registry worked perfectly and the
 * command it pointed at was not there. Nothing below the server line could have caught it, because
 * the machinery was right and only the registration was in the wrong place.
 */
public final class CoreCommands {

    private CoreCommands() {
    }

    /**
     * The callback command chat buttons need.
     *
     * <p>Not a command anybody types — it takes a token and runs whatever was registered against it.
     * Register it under a name nothing else uses and tell {@code buttons()} what you called it, or
     * take the default of {@code rcclick} and leave the button helper alone.
     *
     * @param name what to call it, without a slash
     */
    public static void clickCallback(Commands registrar, String name) {
        registrar.register(name, "Runs a button you clicked in chat.", new ClickCommand());
    }

    /** The same, as {@code rcclick} — which is what {@code buttons()} expects by default. */
    public static void clickCallback(Commands registrar) {
        clickCallback(registrar, "rcclick");
    }

    /** Reading and changing every plugin's settings, from chat. */
    public static void settings(Commands registrar, String name, String... aliases) {
        registrar.register(name, "Everything every plugin on this server can be told to do.",
                List.of(aliases), new SettingsCommand());
    }

    /** Going to a warp, and managing the list of them. */
    public static void warps(Commands registrar, String name, String... aliases) {
        registrar.register(name, "Go to a named place, or manage the list of them.",
                List.of(aliases), new WarpCommand());
    }

    /** Going to a farm world, and regenerating one. */
    public static void farmWorlds(Commands registrar, String name, String... aliases) {
        registrar.register(name, "Go to a farm world, or run one.", List.of(aliases),
                new FarmWorldCommand());
    }
}

```

