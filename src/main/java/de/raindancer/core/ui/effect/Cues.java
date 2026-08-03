package de.raindancer.core.ui.effect;

import java.util.List;

/**
 * The cues every plugin needs, named by what they mean rather than by what they sound like.
 *
 * <h2>Why names and not sounds</h2>
 * Because {@code play(player, Cues.NO)} still makes sense after somebody decides the refusal should
 * be a bass note rather than a villager, and {@code playSound(player, ENTITY_VILLAGER_NO)} does not.
 * Asking by meaning is what lets one line in one place change how every menu in every plugin sounds.
 *
 * <h2>Why there are this many</h2>
 * Because a handful would not be enough to stop anybody. A plugin that cannot find a cue for what it
 * is doing writes its own {@code playSound} and the whole point is lost, so the list has to cover
 * what plugins actually do: refuse things, open menus, teleport people, hurt them, heal them, hand
 * them things, count down at them. Each is bound to a vanilla sound and, where it is something you
 * ought to see as well as hear, to particles too.
 *
 * <p>They are plain strings so a plugin can add its own — {@code "ghastlines:whoosh"} — without
 * anything here knowing about it, and so a server owner can rebind any of them the same way.
 */
public final class Cues {

    private Cues() {
    }

    // ---------------------------------------------------------------- answers

    /** Something worked. */
    public static final String OK = "core:ok";

    /** Something was refused. The most important one to get right: players hear it most. */
    public static final String NO = "core:no";

    /** Something is worth noticing but is not a refusal. */
    public static final String WARN = "core:warn";

    /** Something went wrong on the server's side rather than the player's. */
    public static final String ERROR = "core:error";

    /** Somebody is being spoken to — a message that should not be scrolled past. */
    public static final String NOTIFY = "core:notify";

    // ---------------------------------------------------------------- menus

    /** A button in a menu was pressed. */
    public static final String CLICK = "core:click";

    /** A menu opened. */
    public static final String OPEN = "core:open";

    /** A menu closed. */
    public static final String CLOSE = "core:close";

    /** A page turned, or a list scrolled. */
    public static final String PAGE = "core:page";

    // ---------------------------------------------------------------- moving about

    /** Somebody arrived somewhere. */
    public static final String TELEPORT = "core:teleport";

    /** Something is being counted down — one tick of it. */
    public static final String COUNTDOWN = "core:countdown";

    /** The countdown reached zero. */
    public static final String COUNTDOWN_DONE = "core:countdown-done";

    /** Somebody entered a place that has an owner — a claim, a town, a zone. */
    public static final String ENTER = "core:enter";

    /** And left it again. */
    public static final String LEAVE = "core:leave";

    // ---------------------------------------------------------------- things happening to you

    /** Something was earned — an achievement, a level, a rank. */
    public static final String EARNED = "core:earned";

    /** Something was given — an item, a reward, money. */
    public static final String REWARD = "core:reward";

    /** Somebody was healed or fed. */
    public static final String HEAL = "core:heal";

    /** Somebody was hurt by a plugin rather than by the world. */
    public static final String HURT = "core:hurt";

    /** Something was created out of nothing — a spawned mob, a conjured block. */
    public static final String SUMMON = "core:summon";

    /** Something was taken away — a despawn, a cleared drop, a removed entity. */
    public static final String VANISH = "core:vanish";

    /** Something magical happened that does not fit anywhere else. */
    public static final String MAGIC = "core:magic";

    /** A custom item's ability went off. */
    public static final String ABILITY = "core:ability";

    /** An ability was used and is now on cooldown, or was used too soon. */
    public static final String COOLDOWN = "core:cooldown";

    /** Every cue this ships with, for a menu, a settings page or a test. */
    public static List<String> all() {
        return List.of(OK, NO, WARN, ERROR, NOTIFY,
                CLICK, OPEN, CLOSE, PAGE,
                TELEPORT, COUNTDOWN, COUNTDOWN_DONE, ENTER, LEAVE,
                EARNED, REWARD, HEAL, HURT, SUMMON, VANISH, MAGIC, ABILITY, COOLDOWN);
    }
}
