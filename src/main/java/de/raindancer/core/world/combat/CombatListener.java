package de.raindancer.core.world.combat;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Working out who actually attacked, and cancelling the ones that are not allowed.
 *
 * <h2>Why this class is the hard part</h2>
 * {@link Combat} decides what is allowed and is ten lines of arithmetic. This decides <em>who did
 * it</em>, and that is where every PvP plugin has ever gone wrong, because the server does not say.
 * The event names whatever object delivered the damage:
 *
 * <ul>
 *   <li>an <b>arrow</b>, a trident, a snowball, an ender pearl — the shooter is behind it, and a rule
 *       that only checks the damager lets anybody shoot their way past PvP being off;</li>
 *   <li>a <b>wolf</b> or a tamed cat — the owner set it on somebody, and treating it as a mob turns
 *       PvP into "bring a dog";</li>
 *   <li>a block of <b>primed TNT</b> — whoever lit it, which may be somebody who has since left;</li>
 *   <li>a <b>lingering potion's cloud</b>, which is not the thrower and not a projectile either;</li>
 *   <li>a <b>fishing rod</b>, a firework, a llama's spit, a wither skull.</li>
 * </ul>
 *
 * <p>Each of those has to be followed back to a person, and the chain can be more than one link long:
 * a wolf shot by an arrow fired by a player, or a skeleton's arrow — which is a mob, not nobody.
 *
 * <h2>Two more things it gets right on purpose</h2>
 * <ul>
 *   <li><b>Splash and lingering potions are their own event.</b> A splash of harming hits everybody in
 *       range at once and never produces a damage event naming the thrower, so a plugin listening only
 *       to damage lets a potion do what a sword may not.</li>
 *   <li><b>The refusal is told to the attacker, once.</b> A cancelled event with no message is a
 *       player hitting somebody harder and concluding the server is broken; a message per swing is
 *       chat somebody has to leave to escape. So: throttled, and only ever to the person who did it.</li>
 * </ul>
 *
 * <h2>Priority</h2>
 * {@code LOW}, and {@code ignoreCancelled = true}. Low so that a plugin with a more specific opinion —
 * a claim, an arena — can still see the event and change its mind, and cancelled events are left
 * alone: something else already refused, and a second refusal is not worth a second message.
 */
public final class CombatListener implements Listener {

    private static final LogChannel log = Log.of("combat");

    /** How long before the same player is told again why they cannot. */
    private static final long QUIET_MILLIS = 3_000;

    /** How far back a chain of blame is followed before giving up. */
    private static final int MAX_LINKS = 8;

    private final Combat combat;
    private final LongSupplier clock;
    private final Messages messages;

    /** When each attacker was last told, so a held-down attack is one message and not forty. */
    private final ConcurrentHashMap<UUID, AtomicLong> lastTold = new ConcurrentHashMap<>();

    public CombatListener(Combat combat, LongSupplier clock, Messages messages) {
        this.combat = combat;
        this.clock = clock;
        this.messages = messages;
    }

    // ---------------------------------------------------------------------------- the events

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Attack attack = read(event.getDamager(), event.getEntity());
        Verdict verdict = combat.judge(attack);
        if (!verdict.allowed()) {
            event.setCancelled(true);
            tell(attack, verdict);
        }
    }

    /**
     * A splash potion, which hits everybody in range without a damage event naming the thrower.
     *
     * <p>Each victim is considered separately, because the answer differs per victim: a potion thrown
     * into a crowd may legitimately hit the thrower's own team-mate and not somebody in a claim. A
     * victim who may not be hit has their share of the effect set to zero rather than the whole potion
     * being cancelled — cancelling it would let one protected player standing nearby save everybody.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        UUID thrower = personBehind(event.getPotion().getShooter(), 0).orElse(null);
        if (thrower == null) {
            return;
        }
        Verdict worst = Verdict.ALLOWED;
        for (LivingEntity hit : event.getAffectedEntities()) {
            Attack attack = between(Attack.Fighter.PLAYER, thrower,
                    event.getPotion().getLocation(), hit);
            Verdict verdict = combat.judge(attack);
            if (!verdict.allowed()) {
                event.setIntensity(hit, 0);
                worst = verdict;
            }
        }
        if (!worst.allowed()) {
            tell(between(Attack.Fighter.PLAYER, thrower, event.getPotion().getLocation(),
                    event.getEntity()), worst);
        }
    }

    /** Forgets a player's throttle when they leave. */
    public void forget(UUID who) {
        if (who != null) {
            lastTold.remove(who);
        }
    }

    // ------------------------------------------------------------------- who really did it

    /** Turns "this object damaged that one" into "this person attacked that one". */
    private Attack read(Entity damager, Entity victim) {
        Optional<UUID> who = personBehind(damager, 0);
        Attack.Fighter attacker = who.isPresent() ? Attack.Fighter.PLAYER : kindOf(damager);
        UUID attackerId = who.orElseGet(() -> damager == null ? null : damager.getUniqueId());
        Location from = damager == null ? null : damager.getLocation();
        return between(attacker, attackerId, from, victim);
    }

    private Attack between(Attack.Fighter attacker, UUID attackerId, Location from, Entity victim) {
        Location where = victim == null ? null : victim.getLocation();
        String world = where != null ? where.getWorld().getName()
                : from != null ? from.getWorld().getName() : "";
        return new Attack(attacker, attackerId, kindOf(victim),
                victim == null ? null : victim.getUniqueId(), world, at(where), at(from));
    }

    private static Attack.At at(Location location) {
        return location == null ? null
                : new Attack.At(location.getX(), location.getY(), location.getZ());
    }

    /**
     * Follows a chain of blame back to a person.
     *
     * <p>Recursive, because the chain really can be several links long: a wolf hurt by an arrow fired
     * by a player. Bounded, because a chain that loops — which a badly-behaved plugin can construct by
     * setting an entity as its own shooter — would otherwise be an endless loop on the thread ticking
     * the world.
     *
     * @return the person behind it, or empty when nobody is
     */
    private Optional<UUID> personBehind(Object thing, int depth) {
        if (thing == null || depth > MAX_LINKS) {
            if (depth > MAX_LINKS) {
                log.warn("Gave up following an attack back after {} links; something is pointing at "
                        + "itself.", MAX_LINKS);
            }
            return Optional.empty();
        }
        if (thing instanceof Player player) {
            return Optional.of(player.getUniqueId());
        }
        if (thing instanceof Projectile projectile) {
            // An arrow, a trident, a snowball, a firework, a llama's spit. The shooter may itself be
            // a projectile in silly cases, hence the recursion rather than one step.
            return personBehind(projectile.getShooter(), depth + 1);
        }
        if (thing instanceof AreaEffectCloud cloud) {
            // What a lingering potion leaves behind. Not a projectile, and its source is the thrown
            // potion rather than the thrower — so this is the link a damage-only listener misses.
            return personBehind(cloud.getSource(), depth + 1);
        }
        if (thing instanceof TNTPrimed tnt) {
            // Whoever lit it, which Paper remembers. They may have logged out since, which is fine:
            // a UUID is still who did it.
            return personBehind(tnt.getSource(), depth + 1);
        }
        if (thing instanceof Tameable pet && pet.isTamed()) {
            // A wolf set on somebody is its owner attacking. Without this, PvP off means "bring a
            // dog", which is the oldest way round a PvP rule there is.
            return personBehind(pet.getOwner(), depth + 1);
        }
        if (thing instanceof org.bukkit.OfflinePlayer offline) {
            // What Tameable.getOwner() answers for an owner who is not online.
            return Optional.of(offline.getUniqueId());
        }
        if (thing instanceof ProjectileSource source && source instanceof Entity entity
                && !(entity instanceof Player)) {
            // A skeleton's arrow: a mob, not nobody, and not to be followed further.
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * What something is, as far as the rules care.
     *
     * <p>Anything alive that is not a person is a mob — including somebody's horse, which is
     * deliberate: a rule about hurting animals should cover the animal somebody is fond of.
     */
    private static Attack.Fighter kindOf(Entity entity) {
        if (entity instanceof Player) {
            return Attack.Fighter.PLAYER;
        }
        return entity instanceof LivingEntity ? Attack.Fighter.MOB : Attack.Fighter.NOBODY;
    }

    // ----------------------------------------------------------------------------- telling

    /**
     * Tells the attacker why, at most once every few seconds.
     *
     * <p>Only the attacker: the person being protected did not do anything and does not need to be
     * told about it. And throttled, because a cancelled attack with no message is a player who hits
     * harder and then reports the server as broken, while a message per swing is chat somebody has to
     * log out to escape.
     */
    private void tell(Attack attack, Verdict verdict) {
        if (verdict.reasonKey() == null || messages == null) {
            return;
        }
        UUID who = attack.attackerId();
        if (who == null || attack.attacker() != Attack.Fighter.PLAYER) {
            // A mob being stopped has nobody to tell.
            return;
        }
        long now = clock.getAsLong();
        AtomicLong last = lastTold.computeIfAbsent(who, ignored -> new AtomicLong(0L));
        long previous = last.get();
        if (now - previous < QUIET_MILLIS || !last.compareAndSet(previous, now)) {
            // Either recently told, or another thread got there first — on Folia two hits on two
            // victims can be judged at the same instant. Either way, one message.
            return;
        }
        Player attacker = org.bukkit.Bukkit.getPlayer(who);
        if (attacker != null) {
            Component said = messages.prefixed(verdict.reasonKey());
            // Sent from this thread, which is the one that owns the attacker: the damage event fires
            // in the attacker's own region.
            attacker.sendMessage(said);
        }
    }
}
