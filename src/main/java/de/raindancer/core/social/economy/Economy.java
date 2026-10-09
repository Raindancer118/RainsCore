package de.raindancer.core.social.economy;

import java.util.UUID;

/**
 * A server's money, as any plugin may ask about it: what somebody has, and moving it.
 *
 * <p>Provided by whichever plugin owns the money — the economy module, or a foreign economy reached
 * through Vault — and found with {@link Economies#current()}. A claims fee, a warp toll or a shop in
 * somebody else's plugin charges through here and never needs to know which one is installed.
 *
 * <h2>The contract every implementation keeps</h2>
 * <ul>
 *   <li>Safe from any thread. Vault callers are routinely asynchronous, and a region thread on Folia
 *       is not the main thread.</li>
 *   <li>Nothing throws for an ordinary refusal — not enough money, an unknown player, a frozen
 *       account. Those are {@link EconomyResult}s, because every caller has to handle them anyway.</li>
 *   <li>A transfer is all or nothing: never taken from one account without arriving in the other.</li>
 *   <li>The {@code reason} is recorded where the provider keeps a history; it is a sentence for a
 *       person reading a statement, not a key.</li>
 * </ul>
 */
public interface Economy {

    /** Who provides this, for a log line and a diagnostic — {@code "RainsEconomy"}, {@code "Vault: EssentialsX"}. */
    String name();

    Currency currency();

    boolean hasAccount(UUID player);

    /** Opens an account if there is none. Whether one exists afterwards. */
    default boolean createAccount(UUID player) {
        return hasAccount(player);
    }

    /** What they have. {@link Money#ZERO} for somebody with no account. */
    Money balance(UUID player);

    default boolean has(UUID player, Money amount) {
        return balance(player).isAtLeast(amount);
    }

    EconomyResult deposit(UUID player, Money amount, String reason);

    EconomyResult withdraw(UUID player, Money amount, String reason);

    EconomyResult transfer(UUID from, UUID to, Money amount, String reason);

    /**
     * {@link #deposit(UUID, Money, String)}, saying where the money comes from — {@code reward.mob},
     * {@code jobs.goal} — so the provider can tell what prints money. On a capped server it is paid out of the
     * treasury, and refused with {@link EconomyResult.Outcome#TREASURY_EMPTY} when that cannot.
     */
    default EconomyResult deposit(UUID player, Money amount, String reason, String source) {
        return deposit(player, amount, reason);
    }

    /** {@link #withdraw(UUID, Money, String)}, saying where the money goes — {@code claims.upkeep}, {@code fine}. */
    default EconomyResult withdraw(UUID player, Money amount, String reason, String source) {
        return withdraw(player, amount, reason);
    }

    /**
     * Gives back exactly what a fee just took, because what was paid for did not happen. Unlike a payout it is
     * never refused for an empty treasury and never skimmed toward a debt: the fee made the room, and money
     * handed back is not income.
     */
    default EconomyResult refund(UUID player, Money amount, String reason, String source) {
        return deposit(player, amount, reason, source);
    }

    /** How much money there is and what the treasury holds; empty when the provider does not know. */
    default java.util.Optional<MoneySupply> supply() {
        return java.util.Optional.empty();
    }

    /** The amount, written in this economy's currency. */
    default String format(Money amount) {
        return currency().format(amount);
    }
}
