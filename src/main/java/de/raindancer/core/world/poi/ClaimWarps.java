package de.raindancer.core.world.poi;

import org.bukkit.Material;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A claim's warp point, and which of a player's claims is their main home.
 *
 * <p>Core's because two plugins share it without knowing about each other: the claims plugin writes
 * these — it knows where a claim is, who owns it and when it is renamed or deleted — and the warps plugin
 * reads them for {@code /warp claim} and {@code /warp home <player>}. Kept in the {@link PoiStore} as their
 * own kind, so they persist, survive an unloaded world and show up wherever places are listed.
 *
 * <p>Who may actually go there is not stored here: that is the claim's own teleport-in rule, asked through
 * {@code Land.mayTeleportInto} when the list is drawn and enforced again on arrival.
 */
public final class ClaimWarps {

    public static final String KIND = "claim-warp";
    /** The id of the claim the point belongs to. */
    public static final String TAG_CLAIM = "claim";
    /**
     * The players who call this claim their main home, as comma-separated UUIDs — several, because a claim
     * may have co-owners. Whether somebody may is the claims plugin's to check: only it knows the owners.
     */
    public static final String TAG_MAIN = "main";

    private final PoiStore places;

    public ClaimWarps(PoiStore places) {
        this.places = places;
    }

    /** Every claim's warp point. */
    public List<Poi> all() {
        return places.ofKind(KIND);
    }

    public Optional<Poi> forClaim(String claimId) {
        if (claimId == null) {
            return Optional.empty();
        }
        return all().stream().filter(point -> claimId.equals(claimOf(point))).findFirst();
    }

    /** The warp points of every claim this player owns. */
    public List<Poi> ownedBy(UUID owner) {
        return owner == null ? List.of() : all().stream().filter(point -> owner.equals(point.owner())).toList();
    }

    /** The claim this player calls home, if they have chosen one. */
    public Optional<Poi> mainOf(UUID player) {
        return player == null ? Optional.empty()
                : all().stream().filter(point -> isHomeOf(point, player)).findFirst();
    }

    /**
     * Sets a claim's warp point, replacing any it had — and staying its owner's main home if it was.
     * Whether the spot is inside the claim is the claims plugin's to check before calling this.
     */
    public Poi set(String claimId, String claimName, UUID owner, String world, double x, double y, double z,
                   float yaw, float pitch) {
        Optional<Poi> before = forClaim(claimId);
        Poi.Builder point = Poi.builder(claimName, world, x, y, z)
                .kind(KIND)
                .owner(owner)
                .icon(Material.LODESTONE)
                .shared(true)
                .facing(yaw, pitch)
                .tag(TAG_CLAIM, claimId);
        before.ifPresent(old -> point.id(old.id()));
        before.flatMap(old -> old.tag(TAG_MAIN)).ifPresent(homeOf -> point.tag(TAG_MAIN, homeOf));
        Poi saved = point.build();
        places.save(saved);
        return saved;
    }

    /**
     * Makes this claim {@code player}'s main home, and no other claim. Whether they own it is the caller's
     * to have checked. False when the claim has no warp point.
     */
    public boolean markMain(UUID player, String claimId) {
        Optional<Poi> chosen = forClaim(claimId);
        if (player == null || chosen.isEmpty()) {
            return false;
        }
        mainOf(player).filter(other -> !other.id().equals(chosen.get().id()))
                .ifPresent(other -> places.save(withHome(other, player, false)));
        places.save(withHome(chosen.get(), player, true));
        return true;
    }

    /** Takes away this player's main home, leaving the claim's warp point where it is. */
    public boolean clearMain(UUID player) {
        Optional<Poi> main = mainOf(player);
        main.ifPresent(point -> places.save(withHome(point, player, false)));
        return main.isPresent();
    }

    /** Forgets that this player called this claim home — they no longer own it. */
    public boolean forget(UUID player, String claimId) {
        Optional<Poi> point = forClaim(claimId).filter(found -> isHomeOf(found, player));
        point.ifPresent(found -> places.save(withHome(found, player, false)));
        return point.isPresent();
    }

    /** Follows the claim's new name. */
    public boolean rename(String claimId, String name) {
        Optional<Poi> point = forClaim(claimId);
        point.ifPresent(found -> places.save(found.renamedTo(name)));
        return point.isPresent();
    }

    /** Follows the claim to a new owner — who has not chosen it as their home, and nobody else may now. */
    public boolean reassign(String claimId, UUID owner) {
        Optional<Poi> point = forClaim(claimId);
        point.ifPresent(found -> places.save(found.withOwner(owner).withTag(TAG_MAIN, null)));
        return point.isPresent();
    }

    /** Takes the claim's warp point away — for an owner removing it, or the claim being deleted. */
    public boolean remove(String claimId) {
        return forClaim(claimId).map(point -> places.delete(point.id())).orElse(false);
    }

    public static String claimOf(Poi point) {
        return point.tag(TAG_CLAIM).orElse(null);
    }

    /** The players who call it their main home. A name that is not a UUID is skipped. */
    public static Set<UUID> homeOf(Poi point) {
        Set<UUID> players = new LinkedHashSet<>();
        for (String written : point.tag(TAG_MAIN).orElse("").split(",")) {
            try {
                if (!written.isBlank()) {
                    players.add(UUID.fromString(written.trim()));
                }
            } catch (IllegalArgumentException notAUuid) {
                // A hand-edited entry. One bad one must not take the others with it.
            }
        }
        return players;
    }

    public static boolean isHomeOf(Poi point, UUID player) {
        return player != null && homeOf(point).contains(player);
    }

    private static Poi withHome(Poi point, UUID player, boolean home) {
        Set<UUID> players = homeOf(point);
        if (home) {
            players.add(player);
        } else {
            players.remove(player);
        }
        return point.withTag(TAG_MAIN, players.isEmpty() ? null
                : players.stream().map(UUID::toString).collect(Collectors.joining(",")));
    }
}
