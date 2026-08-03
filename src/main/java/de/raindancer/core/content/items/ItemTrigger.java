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
    LETHAL_DAMAGE
}
