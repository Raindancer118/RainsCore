package de.raindancer.core.social.roles;

/**
 * The role a player has taken — a Cook, a Miner — as other plugins need to know it.
 *
 * @param id     lower case, as the roles plugin names it ("miner")
 * @param colour {@code #rrggbb}
 */
public record HeldRole(String id, String title, String colour) {
}
