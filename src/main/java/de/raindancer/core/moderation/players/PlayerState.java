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
