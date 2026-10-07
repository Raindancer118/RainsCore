package de.raindancer.core.world.poi;

import org.bukkit.Material;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    /** Present, as "true", on the one point that is its owner's main home. */
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
    public Optional<Poi> mainOf(UUID owner) {
        return ownedBy(owner).stream().filter(ClaimWarps::isMain).findFirst();
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
        if (before.map(ClaimWarps::isMain).orElse(false)) {
            point.tag(TAG_MAIN, "true");
        }
        Poi saved = point.build();
        places.save(saved);
        return saved;
    }

    /** Makes this claim its owner's main home, and no other of theirs. False when it is not theirs or has no point. */
    public boolean markMain(UUID owner, String claimId) {
        Optional<Poi> chosen = forClaim(claimId);
        if (owner == null || chosen.isEmpty() || !owner.equals(chosen.get().owner())) {
            return false;
        }
        for (Poi other : ownedBy(owner)) {
            if (isMain(other) && !other.id().equals(chosen.get().id())) {
                places.save(other.withTag(TAG_MAIN, null));
            }
        }
        places.save(chosen.get().withTag(TAG_MAIN, "true"));
        return true;
    }

    /** Takes away this player's main home, leaving the claim's warp point where it is. */
    public boolean clearMain(UUID owner) {
        Optional<Poi> main = mainOf(owner);
        main.ifPresent(point -> places.save(point.withTag(TAG_MAIN, null)));
        return main.isPresent();
    }

    /** Follows the claim's new name. */
    public boolean rename(String claimId, String name) {
        Optional<Poi> point = forClaim(claimId);
        point.ifPresent(found -> places.save(found.renamedTo(name)));
        return point.isPresent();
    }

    /** Follows the claim to a new owner — who has not chosen it as their home, so it is not. */
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

    public static boolean isMain(Poi point) {
        return point.tag(TAG_MAIN).map("true"::equals).orElse(false);
    }
}
