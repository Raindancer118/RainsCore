package de.raindancer.core.moderation.players;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who cannot be hurt, who hurts everything in one hit, and for whom every block gives way at once.
 *
 * <h2>Why these are here rather than in a moderation plugin</h2>
 * Because they are answers to a damage event, and there must be exactly one plugin on the server
 * deciding what a damage event means — {@code Combat} says so in its own class note: two plugins
 * listening at their own priority is how one of them silently loses. So the state lives beside
 * {@link PlayerAdmin}, which already owns healing, feeding, effects and flight, and the listener sits
 * where {@code CombatListener} can be ordered against it.
 *
 * <h2>Why neither survives a restart</h2>
 * Vanish deliberately does: a hidden moderator who forgets they are hidden is a small problem, and
 * being hidden is a fact about them rather than about this session. An <em>invincible</em> player who
 * forgets — and whom nobody remembers granting it to — is a different thing. It is indistinguishable
 * from a bug in the damage system, and somebody will spend an evening looking for one. Retyping
 * {@code /god} after a restart costs four seconds.
 *
 * <h2>Thread safety</h2>
 * Read from damage events, which fire on whichever region thread the fight is on; written from commands
 * and menu clicks. Safe from any thread.
 */
public final class PlayerPowers {

    /**
     * How much damage one hit does when instakill is on.
     *
     * <p>Finite, and deliberately not {@link Double#MAX_VALUE}: that overflows some of the game's own
     * damage arithmetic into a negative number and <em>heals</em> the target, which is a genuinely
     * baffling way for this to fail. Ten thousand kills everything the game has.
     */
    public static final double INSTAKILL_DAMAGE = 10_000.0D;

    private final Set<UUID> invulnerable = ConcurrentHashMap.newKeySet();
    private final Set<UUID> oneHit = ConcurrentHashMap.newKeySet();
    private final Set<UUID> instantBreakers = ConcurrentHashMap.newKeySet();

    // ---------------------------------------------------------------------------- god mode

    /**
     * Turns invulnerability on or off.
     *
     * @return whether this changed anything, so a caller knows whether to say so
     */
    public boolean god(UUID who, boolean on) {
        if (who == null) {
            return false;
        }
        return on ? invulnerable.add(who) : invulnerable.remove(who);
    }

    /** @return whether they are now invulnerable */
    public boolean toggleGod(UUID who) {
        if (who == null) {
            return false;
        }
        if (invulnerable.remove(who)) {
            return false;
        }
        invulnerable.add(who);
        return true;
    }

    /** Whether nothing may hurt them. */
    public boolean isInvulnerable(UUID who) {
        return who != null && invulnerable.contains(who);
    }

    /** Everybody currently invulnerable, as a snapshot — for a diagnostic and for a staff page. */
    public Set<UUID> invulnerable() {
        return Set.copyOf(invulnerable);
    }

    // ---------------------------------------------------------------------------- instakill

    /** @return whether they now kill in one hit */
    public boolean toggleInstakill(UUID who) {
        if (who == null) {
            return false;
        }
        if (oneHit.remove(who)) {
            return false;
        }
        oneHit.add(who);
        return true;
    }

    /** Turns one-hit-kill on or off. @return whether this changed anything */
    public boolean instakill(UUID who, boolean on) {
        if (who == null) {
            return false;
        }
        return on ? oneHit.add(who) : oneHit.remove(who);
    }

    /** Whether anything they hit dies. */
    public boolean killsInOneHit(UUID who) {
        return who != null && oneHit.contains(who);
    }

    /** Everybody currently one-hitting, as a snapshot. */
    public Set<UUID> oneHitting() {
        return Set.copyOf(oneHit);
    }

    // ---------------------------------------------------------------------------- instant breaking

    /**
     * Blocks break the moment they are hit, whatever the block and whatever is being held.
     *
     * <p>Creative-mode breaking in survival, and no more than that: it changes how long a block takes,
     * not whether somebody is allowed to break it. See {@code PlayerPowerListener#onBlockDamage} for
     * why that distinction is load-bearing rather than a nicety.
     *
     * @return whether they now break instantly
     */
    public boolean toggleInstaBreak(UUID who) {
        if (who == null) {
            return false;
        }
        if (instantBreakers.remove(who)) {
            return false;
        }
        instantBreakers.add(who);
        return true;
    }

    /** Turns instant breaking on or off. @return whether this changed anything */
    public boolean instaBreak(UUID who, boolean on) {
        if (who == null) {
            return false;
        }
        return on ? instantBreakers.add(who) : instantBreakers.remove(who);
    }

    /** Whether every block gives way at once for them. */
    public boolean breaksInstantly(UUID who) {
        return who != null && instantBreakers.contains(who);
    }

    /** Everybody currently breaking instantly, as a snapshot. */
    public Set<UUID> instantBreakers() {
        return Set.copyOf(instantBreakers);
    }

    // ---------------------------------------------------------------------------- housekeeping

    /**
     * Drops all three for somebody who has left.
     *
     * <p>See the class note: none of these is meant to outlive a session, and a set that is never
     * cleaned also grows by an entry per player who has ever used one.
     */
    public void forget(UUID who) {
        if (who == null) {
            return;
        }
        invulnerable.remove(who);
        oneHit.remove(who);
        instantBreakers.remove(who);
    }

    /**
     * Drops everything. For a shutdown or a reload.
     *
     * @return how many people had at least one of the three
     */
    public int forgetEverybody() {
        Set<UUID> everybody = new java.util.HashSet<>(invulnerable);
        everybody.addAll(oneHit);
        everybody.addAll(instantBreakers);
        invulnerable.clear();
        oneHit.clear();
        instantBreakers.clear();
        return everybody.size();
    }
}
