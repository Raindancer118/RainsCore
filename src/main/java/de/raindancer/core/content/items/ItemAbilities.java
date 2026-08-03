package de.raindancer.core.content.items;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Whether an item may do its thing right now, and doing it.
 *
 * <h2>What this is for</h2>
 * The Hunger Games items each answer the same three questions before their effect runs — is this
 * the right trigger, is it still cooling down, are there any uses left — and each answered them
 * with its own static map. This answers them once, so a plugin writes the effect and nothing else.
 *
 * <h2>Why the cooldown and the charge are taken together</h2>
 * A client sends several packets for one click, and every hand-rolled version of this fires a
 * one-use item two or three times because the check and the deduction are separate steps. Here the
 * whole decision is one atomic update of one record, so eight threads clicking at the same instant
 * fire it once — which is a test rather than a hope.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. Per-player state is a {@link ConcurrentHashMap} updated with {@code compute},
 * and the effect runs outside that update so a slow ability cannot block another player's click.
 */
public final class ItemAbilities {

    private static final LogChannel log = Log.of("items");

    /** One player's standing with one ability. */
    private record Standing(long lastUsedAt, int used) {
    }

    private final LongSupplier clock;
    private final Map<String, ItemAbility> abilities = new ConcurrentHashMap<>();
    /** Keyed by player and ability, so one player's cooldown never touches another's. */
    private final Map<UUID, Map<String, Standing>> standings = new ConcurrentHashMap<>();

    /** @param clock milliseconds; injected so cooldowns can be tested without waiting for them */
    public ItemAbilities(LongSupplier clock) {
        this.clock = clock;
    }

    /** Registers what an ability does. Replaces any with the same key. */
    public void register(ItemAbility ability) {
        if (ability != null) {
            abilities.put(ability.key(), ability);
        }
    }

    public Optional<ItemAbility> byKey(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(abilities.get(normalise(key)));
    }

    public List<ItemAbility> all() {
        return List.copyOf(abilities.values());
    }

    /**
     * Tries to use an ability.
     *
     * <p>Safe to call on every click of every item: an ability that does not answer to this trigger
     * costs a map lookup and says {@link UseOutcome#WRONG_TRIGGER}.
     */
    public UseResult use(UUID player, String key, ItemTrigger trigger) {
        if (player == null || key == null || trigger == null) {
            return UseResult.UNKNOWN;
        }
        ItemAbility ability = abilities.get(normalise(key));
        if (ability == null) {
            return UseResult.UNKNOWN;
        }
        if (ability.trigger() != trigger) {
            return UseResult.WRONG_TRIGGER;
        }

        long now = clock.getAsLong();
        // Everything that decides whether this may run happens inside one compute, so two clicks
        // arriving together cannot both pass the check.
        UseResult[] verdict = new UseResult[1];
        forPlayer(player).compute(ability.key(), (ignored, standing) -> {
            int used = standing == null ? 0 : standing.used();
            Integer max = ability.maxCharges();
            if (max != null && used >= max) {
                verdict[0] = UseResult.NO_CHARGES;
                return standing;
            }
            Long cooldown = ability.cooldownMillis();
            if (standing != null && cooldown != null) {
                long waited = now - standing.lastUsedAt();
                if (waited < cooldown) {
                    verdict[0] = UseResult.cooling(cooldown - waited);
                    return standing;
                }
            }
            // Claimed provisionally: the effect has not run yet, and if it declines the claim is
            // handed back below. Claiming first is what closes the double-click window.
            verdict[0] = null;
            return new Standing(now, used + 1);
        });

        if (verdict[0] != null) {
            return verdict[0];
        }
        return run(player, ability, trigger, now);
    }

    /** Runs the effect, and hands the claim back if it declines or throws. */
    private UseResult run(UUID player, ItemAbility ability, ItemTrigger trigger, long now) {
        Integer max = ability.maxCharges();
        Standing after = forPlayer(player).get(ability.key());
        Integer left = max == null ? null : Math.max(0, max - (after == null ? 0 : after.used()));
        ItemUse use = new ItemUse(player, ability.key(), trigger, left);
        try {
            if (ability.effect().test(use)) {
                return UseResult.ran(left);
            }
            giveBack(player, ability, now);
            return UseResult.DECLINED;
        } catch (RuntimeException failure) {
            // A hook aimed at nothing is a decline; an ability that threw is a bug, and the player
            // should not pay a cooldown for our mistake either.
            giveBack(player, ability, now);
            log.error(failure, "The '{}' ability failed for {}.", ability.key(), player);
            return UseResult.FAILED;
        }
    }

    /** Undoes a provisional claim — the use did not happen, so it costs nothing. */
    private void giveBack(UUID player, ItemAbility ability, long now) {
        forPlayer(player).compute(ability.key(), (ignored, standing) -> {
            if (standing == null || standing.lastUsedAt() != now) {
                return standing;
            }
            int used = Math.max(0, standing.used() - 1);
            // Zero means never used, which must not read as "cooling down since the epoch".
            return used == 0 ? null : new Standing(0L, used);
        });
    }

    /** How long until this player may use it again. */
    public Optional<Duration> remaining(UUID player, String key) {
        if (player == null || key == null) {
            return Optional.empty();
        }
        ItemAbility ability = abilities.get(normalise(key));
        Standing standing = forPlayer(player).get(normalise(key));
        if (ability == null || standing == null || ability.cooldownMillis() == null) {
            return Optional.empty();
        }
        long left = ability.cooldownMillis() - (clock.getAsLong() - standing.lastUsedAt());
        return left <= 0 ? Optional.empty() : Optional.of(Duration.ofMillis(left));
    }

    /** How many uses this player has left, or empty when the ability is unlimited. */
    public Optional<Integer> chargesLeft(UUID player, String key) {
        if (player == null || key == null) {
            return Optional.empty();
        }
        ItemAbility ability = abilities.get(normalise(key));
        if (ability == null || ability.maxCharges() == null) {
            return Optional.empty();
        }
        Standing standing = forPlayer(player).get(normalise(key));
        return Optional.of(Math.max(0,
                ability.maxCharges() - (standing == null ? 0 : standing.used())));
    }

    /** Gives a player all their cooldowns and charges back — for a round starting again. */
    public void reset(UUID player) {
        if (player != null) {
            standings.remove(player);
        }
    }

    /** Drops everything remembered about a player. Called when they log out. */
    public void forget(UUID player) {
        reset(player);
    }

    private Map<String, Standing> forPlayer(UUID player) {
        return standings.computeIfAbsent(player, key -> new ConcurrentHashMap<>());
    }

    private static String normalise(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
