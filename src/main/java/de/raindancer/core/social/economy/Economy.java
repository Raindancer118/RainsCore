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

    /** The amount, written in this economy's currency. */
    default String format(Money amount) {
        return currency().format(amount);
    }
}
