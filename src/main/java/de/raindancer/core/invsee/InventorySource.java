package de.raindancer.core.invsee;

import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * Where what somebody is carrying comes from.
 *
 * <h2>Why there are two of these</h2>
 * A player who is online has an inventory the server holds in memory. A player who is not has a file
 * on disk, and no {@code getInventory()} anywhere in the API will reach it. Those are genuinely
 * different jobs with genuinely different failure modes — a live inventory cannot be half-written,
 * and a file cannot be changed by its owner mid-edit — so they are two implementations rather than
 * one with a branch in it.
 *
 * <p>Both hand back the same {@link Carried} snapshot, which is what lets the window, the permission
 * rules and the whole of the rest of this package be written once and never ask which kind of player
 * it is looking at.
 */
public interface InventorySource {

    /**
     * What they are carrying, or empty when this source has nothing for them — an offline source
     * asked about somebody who has never joined, or an online one asked about somebody who has
     * logged out since.
     */
    Optional<Carried<ItemStack>> read(UUID who);

    /**
     * Puts one item in one place.
     *
     * <p>One slot rather than the whole snapshot, because that is what a click is. Writing back an
     * entire inventory on every click would undo anything its owner did in between — which for an
     * online player is anything they picked up while the moderator was looking.
     *
     * @return whether it was written
     */
    boolean set(UUID who, Section section, int indexWithin, ItemStack item);

    /**
     * Writes a whole snapshot back at once.
     *
     * <p>What the offline source does when a window closes: one file write rather than one per
     * click. For a live inventory this is the dangerous one and {@link #set} is the safe one.
     *
     * @return whether it was written
     */
    boolean write(UUID who, Carried<ItemStack> carried);

    /** What to call this in a log line or a refusal — "their save file", "their inventory". */
    String describe();
}
