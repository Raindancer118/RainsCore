package de.raindancer.core.world.protection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A piece of ground somebody is responsible for, as much of it as world protection needs to see.
 *
 * <h2>What this is not</h2>
 * Not a claim. A claim is one implementation of it, and a rather elaborate one — it also has a shape, a
 * pantry, a bank, an entry fee, co-owners and a fence, none of which world protection has any business
 * knowing about. An arena, a plot, a spawn region or a farm-world border could each be a
 * {@code ProtectedArea} without acquiring any of that.
 *
 * <p>Which is the whole reason the interface exists. Core enforces flags on areas; whoever owns the areas
 * decides what an area <em>is</em>. Six methods, and the plugin on the other side can be as complicated as
 * it likes behind them.
 *
 * <h2>Threading</h2>
 * Every method is called from whichever region thread an event arrived on, several times a tick. They must
 * be cheap and safe to call concurrently, and must not block.
 */
public interface ProtectedArea {

    /** Whatever the answering plugin calls this ground, stable across a restart. */
    String id();

    /** What to call it to a player, already readable — "Raindancer118's home", not a uuid. */
    String name();

    /** Everybody with a stake in it. First is the one to ask when only one can be asked. */
    List<UUID> owners();

    /**
     * What the person responsible has chosen for this flag, if they have chosen anything.
     *
     * <p>Empty means "not decided here", which is how the server default gets a say. Answering a value for
     * every flag would silently pin every claim on the server to whatever the defaults were the day it was
     * created.
     */
    Optional<Boolean> flagOverride(LandFlag flag, LandAudience audience);

    /**
     * Where this player stands here. Never null — somebody nobody has heard of is a visitor.
     *
     * <p>The three tiers are Core's, and how somebody earns one is not: a claims plugin reads its member
     * list, an arena might make everybody a visitor, a plot world might go by plot ownership.
     */
    LandAudience audienceOf(UUID who);

    /**
     * Whether this player may do that here.
     *
     * <p>The per-player half of the question, and deliberately opaque. Trust lists, delegated management
     * rights, bans, timeouts and whatever else somebody invents all resolve behind this one call, so Core
     * never has to model any of them.
     */
    boolean may(UUID who, LandAction action);

    /** Whether anybody may be here at all — the cheap early exit for the movement path. */
    default boolean mayEnter(UUID who) {
        return may(who, LandAction.ENTER);
    }
}
