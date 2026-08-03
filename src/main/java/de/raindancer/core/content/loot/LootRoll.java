package de.raindancer.core.content.loot;

/**
 * One thing that came out of a table, and where it goes.
 *
 * <p>A value rather than an {@code ItemStack}, so rolling a whole container can be tested without a
 * server. Turning it into a real stack is {@code LootFiller}'s job, and needs one.
 *
 * @param slot which slot of the container it lands in
 */
public record LootRoll(int slot, LootEntry entry, int amount) {
}
