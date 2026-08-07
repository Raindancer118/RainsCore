package de.raindancer.core.content.items;

/**
 * What made a custom item do its thing.
 *
 * <h2>Where this list comes from</h2>
 * Every trigger the Hunger Games items actually use, and nothing else. Its thirteen items between
 * them are fired by a right click, by being hit, by a thrown potion landing, and by damage that
 * would otherwise have been lethal — so those are the cases, rather than a guess at what a trigger
 * enum ought to contain.
 */
public enum ItemTrigger {

    /** Right click, holding it. The usual one: the grappling hook, the leap, the smoke bomb. */
    RIGHT_CLICK,

    /** Left click, holding it. */
    LEFT_CLICK,

    /** Hitting something with it. */
    HIT_ENTITY,

    /** Eating or drinking it. */
    CONSUME,

    /** Dropping it. */
    DROP,

    /**
     * Something thrown landed — a splash potion, a snowball.
     *
     * <p>The item is long gone from the hand by then, so whatever handles this works from the
     * projectile rather than the stack.
     */
    PROJECTILE_HIT,

    /**
     * The holder took damage, at all.
     *
     * <p>What interrupts a medikit's countdown. Fires often, so an ability on this wants to be
     * cheap.
     */
    DAMAGE_TAKEN,

    /**
     * Damage that would otherwise kill the holder.
     *
     * <p>The totem case — the Hunger Games' "trottel-schutz" saves its holder from lethal
     * environmental damage and is consumed doing it. An ability here is expected to say whether it
     * actually saved them, because the damage is only cancelled if it did.
     */
    LETHAL_DAMAGE;

    /**
     * Which of these a {@code PlayerInteractEvent}'s action is, if it is one at all.
     *
     * <p>Here rather than in the listener because of the bug it exists to prevent: Bukkit reports a
     * right click as {@code RIGHT_CLICK_AIR} <em>or</em> {@code RIGHT_CLICK_BLOCK} depending on what
     * happened to be in front of the player, and every hand-rolled version of this check has
     * eventually handled one and forgotten the other — after which an item works in the open and does
     * nothing when its holder is standing next to a wall, which is not a bug report anybody files
     * usefully.
     *
     * <p>Physical (a pressure plate, a tripwire) is deliberately absent: that is the world acting on
     * the player rather than the player using what they are holding.
     */
    public static java.util.Optional<ItemTrigger> forClick(org.bukkit.event.block.Action action) {
        if (action == null) {
            return java.util.Optional.empty();
        }
        return switch (action) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> java.util.Optional.of(RIGHT_CLICK);
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> java.util.Optional.of(LEFT_CLICK);
            default -> java.util.Optional.empty();
        };
    }
}
