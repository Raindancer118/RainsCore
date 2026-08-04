package de.raindancer.core.world.warp;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.world.poi.Poi;
import de.raindancer.core.world.poi.PoiStore;
import org.bukkit.Location;
import org.bukkit.Material;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Named places anybody can be sent to.
 *
 * <h2>Why this has no store of its own</h2>
 * Because there already is one, and a warp is not a different kind of thing from a home or a ghast
 * stop — it is a place with a name, a world and coordinates. So a warp is a {@link Poi} of kind
 * {@code warp}, and persistence, atomic writes, worlds that are not loaded and "is this reachable"
 * are already solved and already tested. What is left is what a warp actually adds: who may use one,
 * how often, and how they are listed.
 *
 * <p>That is not only tidiness. It means everything which already understands places understands
 * warps: a ghast line can fly somebody to one, a menu can list them beside homes, and deleting a
 * world takes its warps with it. None of that would work if warps were a second store that happened
 * to look the same.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. The places are the {@link PoiStore}'s problem and it is already safe; the
 * cooldowns are a {@link ConcurrentHashMap}.
 */
public final class Warps {

    private static final LogChannel log = Log.of("warps");

    /** The kind these are stored under, so they can be told apart from homes and stops. */
    public static final String KIND = "warp";

    private final PoiStore places;
    /**
     * Whether a world is loaded.
     *
     * <p>A seam, because {@code Poi#isReachable} asks {@link org.bukkit.Bukkit}, and every rule in
     * this class would otherwise need a running server to test — including the one that matters
     * most, that a warp into an unloaded world is refused rather than silently doing nothing.
     */
    private final Predicate<String> worldLoaded;
    /** When each player last warped. One cooldown per player, not one per warp — see {@link #use}. */
    private final Cooldowns<UUID> waits;

    /** @param clock milliseconds; injected so cooldowns can be tested without waiting */
    public Warps(PoiStore places, LongSupplier clock) {
        this(places, clock, world -> org.bukkit.Bukkit.getWorld(world) != null);
    }

    /** @param worldLoaded whether a world is loaded; the seam that keeps this testable */
    public Warps(PoiStore places, LongSupplier clock, Predicate<String> worldLoaded) {
        this.places = places;
        this.worldLoaded = worldLoaded;
        this.waits = new Cooldowns<>(clock);
    }

    /** How long between one player's warps. Null switches it off. */
    public void cooldown(Duration between) {
        waits.every(between);
    }

    public Optional<Duration> cooldown() {
        return waits.every();
    }

    // ---------------------------------------------------------------------------- making them

    /**
     * Creates a warp, replacing any of the same name.
     *
     * @return the warp, or empty when the name was blank
     */
    public Optional<Warp> create(String name, String world, double x, double y, double z,
                                 UUID creator) {
        if (name == null || name.isBlank() || world == null || world.isBlank()) {
            return Optional.empty();
        }
        String clean = name.trim();
        // The same name replaces rather than adding a second: two warps called "spawn" is a warp
        // command that reaches whichever the store happened to list first.
        byName(clean).ifPresent(existing -> places.delete(existing.poi().id()));

        Poi place = Poi.builder(clean, world, x, y, z)
                .kind(KIND)
                .owner(creator)
                .icon(Material.LODESTONE)
                .shared(true)
                .build();
        places.save(place);
        return Optional.of(new Warp(place));
    }

    /** The same, from somewhere a player is standing — keeping which way they face. */
    public Optional<Warp> create(String name, Location where, UUID creator) {
        if (where == null || where.getWorld() == null) {
            return Optional.empty();
        }
        Optional<Warp> made = create(name, where.getWorld().getName(),
                where.getX(), where.getY(), where.getZ(), creator);
        made.ifPresent(warp -> {
            Poi facing = Poi.builder(warp.name(), warp.world(), where.getX(), where.getY(),
                            where.getZ())
                    .id(warp.poi().id())
                    .kind(KIND)
                    .owner(creator)
                    .icon(warp.poi().icon())
                    .shared(true)
                    .facing(where.getYaw(), where.getPitch())
                    .build();
            places.save(facing);
        });
        return byName(name);
    }

    /** Forgets a warp. */
    public boolean delete(String name) {
        return byName(name).map(warp -> places.delete(warp.poi().id())).orElse(false);
    }

    // ---------------------------------------------------------------------------- changing them

    /**
     * Moves an existing warp somewhere else, keeping everything that was configured on it.
     *
     * <p>Not {@code create} again under the same name. That replaces, and replacing loses the
     * permission, the category, the icon and the id — so "the spawn warp is two blocks into a wall,
     * let me redo it" would silently open a staff warp to the whole server. This is the one that has
     * been safe to type since.
     *
     * @return true when there was a warp of that name to move
     */
    public boolean move(String name, String world, double x, double y, double z,
                        float yaw, float pitch) {
        if (world == null || world.isBlank()) {
            return false;
        }
        return byName(name).map(warp -> {
            // The facing goes with it: movedTo keeps the tags, the icon and the label, and a warp
            // that drops you looking at a wall is one everybody turns round in the moment they land.
            places.save(warp.poi().movedTo(world, x, y, z).withFacing(yaw, pitch));
            return true;
        }).orElse(false);
    }

    /**
     * The same, from where somebody is standing.
     *
     * <p>The convenience over the one above, which takes plain values so that the rule — that
     * moving keeps what was configured — can be tested without a running server.
     */
    public boolean move(String name, Location where) {
        if (where == null || where.getWorld() == null) {
            return false;
        }
        return move(name, where.getWorld().getName(), where.getX(), where.getY(), where.getZ(),
                where.getYaw(), where.getPitch());
    }

    /**
     * What a menu calls it, when that should differ from the name typed at the command.
     *
     * <p>Null puts it back to being called by its name. Kept apart from the name deliberately: the
     * name is what {@code /warp <name>} takes and what a permission was written against, so a warp
     * that wants a space or a capital in a menu must not have to change either.
     */
    public boolean setLabel(String name, String label) {
        return byName(name).map(warp -> {
            places.save(warp.poi().withLabel(label == null || label.isBlank() ? null : label.trim()));
            return true;
        }).orElse(false);
    }

    /** Who may use it. Null opens it to everybody. */
    public boolean setPermission(String name, String permission) {
        return retag(name, Warp.TAG_PERMISSION, permission);
    }

    /** What it is filed under, for a menu that groups them. Null takes it out of any category. */
    public boolean setCategory(String name, String category) {
        return retag(name, Warp.TAG_CATEGORY, category);
    }

    /** The block it shows as in a menu. */
    public boolean setIcon(String name, Material icon) {
        return byName(name).map(warp -> {
            places.save(warp.poi().withIcon(icon));
            return true;
        }).orElse(false);
    }

    private boolean retag(String name, String key, String value) {
        return byName(name).map(warp -> {
            places.save(warp.poi().withTag(key, value == null || value.isBlank() ? null : value));
            return true;
        }).orElse(false);
    }

    // ---------------------------------------------------------------------------- finding them

    /** One warp by name, however it was capitalised. */
    public Optional<Warp> byName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        return places.ofKind(KIND).stream()
                .filter(place -> place.name().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst()
                .map(Warp::new);
    }

    /** Every warp, in alphabetical order — what a command completes and a menu lists. */
    public List<Warp> all() {
        return places.ofKind(KIND).stream()
                .map(Warp::new)
                .sorted(Comparator.comparing(Warp::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public List<String> names() {
        return all().stream().map(Warp::name).toList();
    }

    /** Every warp in a category; null gives the ones in none. */
    public List<Warp> inCategory(String category) {
        return all().stream()
                .filter(warp -> category == null
                        ? warp.category().isEmpty()
                        : warp.category().map(category::equalsIgnoreCase).orElse(false))
                .toList();
    }

    /** Every category anybody has used, in order. */
    public Set<String> categories() {
        Set<String> found = new LinkedHashSet<>();
        for (Warp warp : all()) {
            warp.category().ifPresent(found::add);
        }
        return Set.copyOf(found);
    }

    /**
     * The warps this player may see.
     *
     * @param hasPermission how to ask whether they have one — a method reference to
     *                      {@code Player::hasPermission} in production, a lambda in a test
     */
    public List<Warp> visibleTo(UUID player, Predicate<String> hasPermission) {
        return all().stream()
                .filter(warp -> warp.permission()
                        .map(permission -> hasPermission != null && hasPermission.test(permission))
                        .orElse(true))
                .toList();
    }

    /** Whether this player may use that warp. */
    public boolean mayUse(UUID player, String name, Predicate<String> hasPermission) {
        return byName(name)
                .map(warp -> warp.permission()
                        .map(permission -> hasPermission != null && hasPermission.test(permission))
                        .orElse(true))
                .orElse(false);
    }

    // ---------------------------------------------------------------------------- using them

    /**
     * Whether this player may warp there right now, and records it if so.
     *
     * <p>Does <em>not</em> teleport: that needs a region thread and a {@code Player}, and keeping it
     * out means every rule here is testable. The caller does the moving on a {@link WarpUse#WENT}.
     *
     * <p>The cooldown is one per player rather than one per warp, deliberately: per-warp cooldowns
     * mean hopping between two warps costs nothing at all, which is the same as having none.
     */
    public WarpUse use(UUID player, String name) {
        if (player == null) {
            return WarpUse.UNKNOWN;
        }
        Optional<Warp> found = byName(name);
        if (found.isEmpty()) {
            return WarpUse.UNKNOWN;
        }
        Warp warp = found.get();
        if (!worldLoaded.test(warp.world())) {
            // Not deleted and not an error: a multiverse server unloads worlds for maintenance and
            // the warp works again when the world comes back.
            return WarpUse.WORLD_MISSING;
        }
        // Checked and recorded in one step, by Cooldowns. Reading the last use and then writing it,
        // as two steps, lets two requests arriving together both see the old value and both be
        // allowed — a macro or a double-click getting a free warp past the cooldown, and on Folia
        // those really are two threads.
        //
        // Asked only once everything else has passed, so a typo or a locked warp does not cost
        // thirty seconds.
        if (!waits.tryUse(player)) {
            return WarpUse.ON_COOLDOWN;
        }
        return WarpUse.WENT;
    }

    /** How long until this player may warp again. */
    public Optional<Duration> remaining(UUID player) {
        return waits.remaining(player);
    }

    /**
     * Whether this player's wait is over, <em>without</em> spending their go.
     *
     * <p>For the caller that does not teleport straight away. {@link #use} charges up front, which is
     * right when the teleport happens on the next line; a caller with a warm-up in between would have
     * to give the charge back when somebody is knocked out of it — and giving it back means clearing
     * the wait, which wipes whatever else was on it. So: ask this, do the journey, and
     * {@link #recordUse} when they actually arrive.
     *
     * <p>Also what a screen asks to grey a button. Opening a menu must not put somebody on cooldown
     * for a warp they never took.
     */
    public boolean isReadyToWarp(UUID player) {
        return waits.isReady(player);
    }

    /**
     * Starts this player's wait, for a caller that has already decided.
     *
     * <p>The other half of {@link #isReadyToWarp}: called once somebody has actually arrived, so a
     * warm-up they were knocked out of costs them nothing.
     */
    public void recordUse(UUID player) {
        waits.start(player);
    }

    /** Where a warp actually is, when its world is loaded. */
    public Optional<Location> locationOf(String name) {
        return byName(name).flatMap(warp -> warp.poi().location());
    }

    /** Forgets a player's cooldown. Called when they log out. */
    public void forget(UUID player) {
        waits.forget(player);
    }
}
