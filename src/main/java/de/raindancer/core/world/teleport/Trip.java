package de.raindancer.core.world.teleport;

/**
 * What sort of journey this is: how long the warm-up, and whether the arrival is checked.
 *
 * <p>A record rather than five arguments to {@code go}, because four of the five are the same on
 * nearly every call and two of them are numbers of the same type — which is how a search radius ends
 * up in the warm-up.
 *
 * @param what        what the traveller is going to, for the line that says so — "spawn", "home"
 * @param warmupSeconds how long they must stand still; zero sends them at once
 * @param safeArrival whether to look for somewhere safe near the destination first
 * @param searchRadius how far to look, when it does; also how much world is pulled in to find out,
 *                    so a large one is a stall on somebody else's machine
 * @param companions  what travels with them — see {@link Companions}
 */
public record Trip(String what, int warmupSeconds, boolean safeArrival, int searchRadius,
                   Companions companions) {

    public Trip {
        companions = companions == null ? Companions.NOBODY : companions;
    }

    /** The sane defaults: no waiting, never dropped into lava, and nothing dragged along. */
    public static Trip to(String what) {
        return new Trip(what, 0, true, 8, Companions.NOBODY);
    }

    /** The same, after standing still for this many seconds. */
    public Trip after(int seconds) {
        return new Trip(what, Math.max(0, seconds), safeArrival, searchRadius, companions);
    }

    /**
     * The same, bringing what the policy says.
     *
     * <p>Worth pairing with a warm-up: something on a lead has to still be on it when the countdown
     * finishes, and a warm-up is what gives somebody time to notice they have dropped it.
     */
    public Trip bringing(Companions bring) {
        return new Trip(what, warmupSeconds, safeArrival, searchRadius, bring);
    }

    /**
     * Straight to the coordinates, whatever is there.
     *
     * <p>For a destination that is known good and must not be moved from — an arena spawn, a warp an
     * admin placed deliberately in mid-air over a drop. Everything a player chose the position of
     * should keep the check.
     */
    public Trip exactly() {
        return new Trip(what, warmupSeconds, false, searchRadius, companions);
    }

    /** How far to look for somewhere safe. */
    public Trip searching(int radius) {
        return new Trip(what, warmupSeconds, safeArrival, Math.max(1, radius), companions);
    }

    public boolean hasWarmup() {
        return warmupSeconds > 0;
    }

    /** Whether anything travels with them. */
    public boolean bringsCompanions() {
        return companions.bringsAnything();
    }
}
