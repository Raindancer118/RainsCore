package de.raindancer.core.world.teleport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Which of the things around somebody travel with them.
 *
 * <h2>Why this is separate from {@link Travel}</h2>
 * Because the failures are the sort nobody reports: a dog left behind on the far side of the world,
 * or a stranger's horse dragged across it. Both look like the plugin working until somebody notices
 * what is missing, and neither would ever be found by trying it once on a test server. So the
 * decision is a function over {@link Candidate} — a handful of plain facts — and can be asked a
 * hundred ways in a second. {@code Travel} is what reads those facts off a running server.
 *
 * <h2>The two rules no policy can turn off</h2>
 * <ul>
 *   <li><b>Another player never comes</b>, at any range, on any lead — <em>and neither does anything
 *       carrying one</em>. Paper moves a vehicle with everything riding in it, so a towed boat with a
 *       stranger in it would be a teleport that stranger never agreed to. On a PvP server, towing
 *       somebody and warping over a drop is a weapon.</li>
 *   <li><b>Somebody else's tame animal never comes</b>, even on the traveller's own lead. Anybody may
 *       leash anybody's wolf or horse, so a lead alone would make a warp the quickest way to take
 *       another player's animals off them.</li>
 * </ul>
 */
public final class Entourage {

    /**
     * What is known about one thing standing near the traveller.
     *
     * <p>Plain values, and deliberately not an {@code Entity}: the whole point is that this can be
     * judged without a server. {@code Travel} fills these in.
     *
     * @param entity     which one, so a caller can find it again
     * @param isPlayer   whether it is a person. Nothing brings one
     * @param leadHeldBy who is holding its lead, or null. Also set for the vehicle the traveller is
     *                   riding and for whatever else is riding with them — from the traveller's point
     *                   of view those are the same "I am taking this with me"
     * @param tamedBy    who it belongs to, or null for a wild thing
     * @param blocksAway how far away, rounded up
     * @param isTame     whether it is a tame animal at all
     * @param carriesAPlayer whether somebody is sitting in or on it. Paper moves a vehicle with
     *                   everything riding in it, so a towed boat with a stranger in it is a way to
     *                   teleport that stranger — which no policy here allows
     */
    public record Candidate(UUID entity, boolean isPlayer, UUID leadHeldBy, UUID tamedBy,
                            int blocksAway, boolean isTame, boolean carriesAPlayer) {

        /**
         * Whether this traveller is the one holding it, <em>and</em> it is theirs to take.
         *
         * <p>The second half is not pedantry. Anybody may put a lead on anybody's tamed wolf or
         * horse, so "it is on my lead" alone would make a warp the quickest way to take somebody's
         * animals off them — from inside their own claim, in one click.
         *
         * <p>A wild thing on a lead is still fair game: villagers in a boat, a llama just caught, a
         * squid on a string. Nobody owns those, so there is nobody to take them from, and they are
         * most of what the feature is for.
         */
        boolean isLedBy(UUID traveller) {
            if (leadHeldBy == null || !leadHeldBy.equals(traveller)) {
                return false;
            }
            return !isTame || belongsTo(traveller);
        }

        /** Whether it is this traveller's own animal. */
        boolean belongsTo(UUID traveller) {
            return isTame && tamedBy != null && tamedBy.equals(traveller);
        }
    }

    private final Companions policy;

    public Entourage(Companions policy) {
        this.policy = policy == null ? Companions.NOBODY : policy;
    }

    public Companions policy() {
        return policy;
    }

    /**
     * Whether it is worth looking around at all.
     *
     * <p>The gather on a live server is a radius search on every warp. A server with this switched
     * off should not be paying for one.
     */
    public boolean isWorthLooking() {
        return policy.bringsAnything();
    }

    /**
     * Whether this one travels.
     *
     * <p>The order matters: the player rule first, because it cannot be overridden; then the lead,
     * which has no range of its own — a lead has its own length and the server already enforces it,
     * so a second shorter range here would leave a boat trailing at full length behind.
     */
    public boolean comesAlong(Candidate candidate, UUID traveller) {
        if (candidate == null || traveller == null || !policy.bringsAnything()) {
            return false;
        }
        if (candidate.isPlayer() || candidate.carriesAPlayer()) {
            // Paper carries a vehicle's passengers with it, so bringing the boat brings whoever is
            // sitting in it. A player moved somewhere they did not ask to go is a teleport nobody
            // agreed to, and on a PvP server towing somebody over a drop is a weapon.
            return false;
        }
        if (candidate.isLedBy(traveller)) {
            return true;
        }
        return policy.bringsNearbyPets()
                && candidate.belongsTo(traveller)
                && candidate.blocksAway() <= policy.radius();
    }

    /**
     * Everything that travels, at most as many as the policy allows.
     *
     * <p>What is on a lead is taken first. When the ceiling bites, the thing somebody deliberately put
     * on a lead is the thing they meant to bring — losing that and keeping a stray cat is the wrong
     * way round. Nearest first among the rest, for the same reason.
     */
    public List<Candidate> from(List<Candidate> around, UUID traveller) {
        if (around == null || around.isEmpty() || !policy.bringsAnything()) {
            return List.of();
        }
        List<Candidate> coming = new ArrayList<>();
        for (Candidate candidate : around) {
            if (comesAlong(candidate, traveller)) {
                coming.add(candidate);
            }
        }
        coming.sort(Comparator
                .comparing((Candidate candidate) -> candidate.isLedBy(traveller) ? 0 : 1)
                .thenComparingInt(Candidate::blocksAway));
        return coming.size() <= policy.most()
                ? List.copyOf(coming)
                : List.copyOf(coming.subList(0, policy.most()));
    }
}
