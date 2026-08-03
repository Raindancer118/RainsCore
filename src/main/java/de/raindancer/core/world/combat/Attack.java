package de.raindancer.core.world.combat;

import java.util.Optional;
import java.util.UUID;

/**
 * One thing hurting another, once the server's version of it has been untangled.
 *
 * <h2>Why this is a value and not the event</h2>
 * Because "who attacked" is the whole problem, and it is not what the event says. The event names
 * whatever object delivered the damage: an arrow, a wolf, a splash of potion, a block of TNT. Every
 * one of those has somebody behind it, and a rule written against the event rather than against the
 * person is a rule anybody can walk around — shoot instead of hit, and PvP is back on.
 *
 * <p>So the untangling happens once, at the edge, and everything above it reasons about people. That
 * also makes the rules testable without a server, which is where the rules actually go wrong.
 *
 * @param attacker   what is doing the hurting, after the chain has been followed back
 * @param attackerId who they are, when there is somebody
 * @param victim     what is being hurt
 * @param victimId   who that is
 * @param world      where, so a server can allow in one world what it forbids in another
 * @param throughPet whether the attacker acted through a tamed animal rather than in person. A
 *                   wolf is still its owner attacking — that is the point of tracing owners — but it
 *                   is not the owner *swinging*, and a rule about players fighting creatures should
 *                   not stop a pet defending them
 * @param where      the spot the damage lands on — the victim. Null when the caller could not say
 * @param from       the spot it came from — the attacker. Null for the world itself, and for an
 *                   attacker whose position is not knowable
 */
public record Attack(Fighter attacker, UUID attackerId, Fighter victim, UUID victimId,
                     String world, boolean throughPet, At where, At from) {

    /**
     * A point in the world, without dragging Bukkit into the rules.
     *
     * <p>Its own tiny type rather than a {@code Location} because everything above the listener is
     * tested without a server, and a {@code Location} holds a {@code World} — which holds the world.
     */
    public record At(double x, double y, double z) {

        /** The block this point is in, which is what a claim is measured in. */
        public int blockX() {
            return (int) Math.floor(x);
        }

        public int blockY() {
            return (int) Math.floor(y);
        }

        public int blockZ() {
            return (int) Math.floor(z);
        }

        /** How far apart two points are, ignoring height — for a rule about range. */
        public double flatDistanceTo(At other) {
            if (other == null) {
                return Double.MAX_VALUE;
            }
            double dx = x - other.x;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    /** What either side of an attack is, as far as the rules care. */
    public enum Fighter {

        /** A person. */
        PLAYER,

        /** Anything alive that is not a person: a zombie, a cow, somebody's horse. */
        MOB,

        /**
         * Nothing that can be held responsible: falling, lava, a cactus, a dispenser nobody aimed.
         *
         * <p>Never refused. A server that stops the environment hurting people has turned off the
         * game, not a rule, and the plugin that did it will be blamed for something nobody asked for.
         */
        NOBODY
    }

    public Attack {
        world = world == null ? "" : world;
    }

    /** Damage from the world itself — falling, drowning, a cactus. */
    public static Attack fromNothing(Fighter victim, UUID victimId, String world) {
        return new Attack(Fighter.NOBODY, null, victim, victimId, world, false, null, null);
    }

    /** One person hitting another. */
    public static Attack between(UUID attacker, UUID victim, String world) {
        return new Attack(Fighter.PLAYER, attacker, Fighter.PLAYER, victim, world, false, null, null);
    }

    /** A person hitting something alive that is not a person. */
    public static Attack onMob(UUID attacker, UUID mob, String world) {
        return new Attack(Fighter.PLAYER, attacker, Fighter.MOB, mob, world, false, null, null);
    }

    /** Something alive hitting a person. */
    public static Attack byMob(UUID mob, UUID victim, String world) {
        return new Attack(Fighter.MOB, mob, Fighter.PLAYER, victim, world, false, null, null);
    }

    /** Two things alive, of whichever kinds — the general form, for a caller with no position. */
    public static Attack of(Fighter attacker, UUID attackerId, Fighter victim, UUID victimId,
                            String world) {
        return new Attack(attacker, attackerId, victim, victimId, world, false, null, null);
    }

    /** The same attack with the two positions filled in. */
    public Attack at(At where, At from) {
        return new Attack(attacker, attackerId, victim, victimId, world, throughPet, where, from);
    }

    /** The same attack, marked as having gone through somebody's pet. */
    public Attack throughAPet() {
        return new Attack(attacker, attackerId, victim, victimId, world, true, where, from);
    }

    /**
     * Where the damage lands.
     *
     * <p>The one a claim asks about: being inside somebody's claim is what protects you, wherever the
     * arrow was fired from.
     */
    public Optional<At> victimSpot() {
        return Optional.ofNullable(where);
    }

    /**
     * Where it came from.
     *
     * <p>The other half of the question a claim actually has, and the one that is easy to forget:
     * standing outside a claim and shooting in is the way round that a rule checking only the victim
     * lets through — and the way round that gets used.
     */
    public Optional<At> attackerSpot() {
        return Optional.ofNullable(from);
    }

    /**
     * Whether both sides were in the same place, within a block or so.
     *
     * <p>For a rule that wants to treat a melee hit differently from a shot across a boundary.
     */
    public boolean isCloseQuarters() {
        return where != null && from != null && where.flatDistanceTo(from) <= 2.0;
    }

    public Optional<UUID> attackerOf() {
        return Optional.ofNullable(attackerId);
    }

    public Optional<UUID> victimOf() {
        return Optional.ofNullable(victimId);
    }

    /** One person hurting another — what "PvP" means. */
    public boolean isPlayerVersusPlayer() {
        return attacker == Fighter.PLAYER && victim == Fighter.PLAYER && !isSelfInflicted();
    }

    /**
     * A person and something alive that is not one, in either direction — what "PvE" means.
     *
     * <p>Both directions, because a server that stops players killing mobs and leaves mobs killing
     * players has made the game worse rather than gentler. Whether the two are switched separately is
     * the caller's business; see {@code Combat}.
     */
    public boolean isPlayerVersusMob() {
        return (attacker == Fighter.PLAYER && victim == Fighter.MOB)
                || (attacker == Fighter.MOB && victim == Fighter.PLAYER);
    }

    /** A player hurting a mob, specifically. */
    public boolean isPlayerHurtingMob() {
        return attacker == Fighter.PLAYER && victim == Fighter.MOB;
    }

    /** A mob hurting a player, specifically. */
    public boolean isMobHurtingPlayer() {
        return attacker == Fighter.MOB && victim == Fighter.PLAYER;
    }

    /** Two mobs, which is the game playing itself and nobody's rule to make. */
    public boolean isMobVersusMob() {
        return attacker == Fighter.MOB && victim == Fighter.MOB;
    }

    /**
     * Somebody hurting themselves.
     *
     * <p>Always allowed, and worth its own question: a player's own arrow coming down on their head,
     * or their own TNT, arrives as an attack by them on them. Refusing it as PvP is how a plugin
     * makes somebody immortal to their own explosives — which sounds harmless until it is how people
     * mine.
     */
    public boolean isSelfInflicted() {
        return attackerId != null && attackerId.equals(victimId);
    }

    /** Whether anybody can be held responsible at all. */
    public boolean hasSomebodyBehindIt() {
        return attacker != Fighter.NOBODY;
    }
}
