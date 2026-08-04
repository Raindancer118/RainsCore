package de.raindancer.core.world.teleport;

/**
 * What travels with somebody: nothing, what they are leading, or that plus their animals nearby.
 *
 * <h2>Why three and not a boolean</h2>
 * Because the middle one is what people actually mean. Somebody who has put a dog on a lead, or is
 * towing a boat with two villagers in it, has said what they want to bring — and bringing exactly
 * that is unsurprising. "Everything nearby" is a different feature with a different failure: a warp
 * taken at a mob farm arrives with the mob farm.
 *
 * <p>{@link #WHAT_YOU_LEAD_AND_NEARBY_PETS} is for the server that wants the dog following you about
 * to come too, which is friendlier and costs a radius search on every warp.
 *
 * <h2>What is not a policy here</h2>
 * Other players. No setting brings one, at any range, on any lead — see {@link Entourage}. A player
 * moved somewhere they did not ask to go is a teleport nobody consented to, and on a PvP server it is
 * a weapon.
 *
 * @param kind   which of the three
 * @param radius how far to look for animals, when the policy looks at all
 * @param most   how many may come; a hundred entities teleported at once is a stall on everybody's
 *               machine, and somebody will try it
 */
public record Companions(Kind kind, int radius, int most) {

    /** The three answers. */
    public enum Kind {

        /** Nothing comes. */
        NOBODY,

        /** Whatever they hold a lead on, and whatever is in the boat or on the horse with them. */
        WHAT_YOU_LEAD,

        /** That, and their own tame animals standing nearby. */
        WHAT_YOU_LEAD_AND_NEARBY_PETS
    }

    /** How far animals may be, at most. Also how much work a warp does; see {@link #within}. */
    public static final int FURTHEST = 32;

    /** How many may come, at most. */
    public static final int MOST_ALLOWED = 20;

    public Companions {
        radius = Math.max(1, Math.min(FURTHEST, radius));
        most = Math.max(1, Math.min(MOST_ALLOWED, most));
    }

    public static final Companions NOBODY = new Companions(Kind.NOBODY, 1, 1);

    public static final Companions WHAT_YOU_LEAD = new Companions(Kind.WHAT_YOU_LEAD, 1, 10);

    public static final Companions WHAT_YOU_LEAD_AND_NEARBY_PETS =
            new Companions(Kind.WHAT_YOU_LEAD_AND_NEARBY_PETS, 8, 10);

    /**
     * The same policy, looking this far.
     *
     * <p>Clamped rather than refused: a radius is a number in a config file, and a server that typed
     * a thousand should get the largest sensible search rather than an exception at the first warp.
     */
    public Companions within(int blocks) {
        return new Companions(kind, blocks, most);
    }

    /** The same policy, bringing at most this many. */
    public Companions atMost(int howMany) {
        return new Companions(kind, radius, howMany);
    }

    /** Whether anything at all travels under this policy. */
    public boolean bringsAnything() {
        return kind != Kind.NOBODY;
    }

    /** Whether animals standing nearby travel, as well as what is on a lead. */
    public boolean bringsNearbyPets() {
        return kind == Kind.WHAT_YOU_LEAD_AND_NEARBY_PETS;
    }
}
