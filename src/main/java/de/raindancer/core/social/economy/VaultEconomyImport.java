package de.raindancer.core.social.economy;

import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * A foreign Vault economy — EssentialsX, CMI — seen as a Rain {@link Economy}, used only while no Rain
 * plugin provides money.
 *
 * <p>Vault has no transfer, so one is a withdrawal followed by a deposit, and a deposit that fails puts
 * the withdrawal back. That is as atomic as Vault allows.
 */
final class VaultEconomyImport implements Economy {

    private final net.milkbowl.vault.economy.Economy vault;
    private final Function<UUID, OfflinePlayer> players;

    VaultEconomyImport(net.milkbowl.vault.economy.Economy vault, Function<UUID, OfflinePlayer> players) {
        this.vault = vault;
        this.players = players;
    }

    net.milkbowl.vault.economy.Economy vault() {
        return vault;
    }

    @Override
    public String name() {
        return "Vault: " + vault.getName();
    }

    @Override
    public Currency currency() {
        int digits = vault.fractionalDigits();
        return Currency.DEFAULT.withNames(vault.currencyNameSingular(), vault.currencyNamePlural())
                .withSymbol("").withDecimals(digits < 0 ? 2 : digits);
    }

    @Override
    public boolean hasAccount(UUID player) {
        return vault.hasAccount(players.apply(player));
    }

    @Override
    public boolean createAccount(UUID player) {
        OfflinePlayer who = players.apply(player);
        return vault.hasAccount(who) || vault.createPlayerAccount(who);
    }

    @Override
    public Money balance(UUID player) {
        return VaultAmounts.toMoney(vault.getBalance(players.apply(player)), currency(), RoundingMode.DOWN)
                .orElse(Money.ZERO);
    }

    @Override
    public EconomyResult deposit(UUID player, Money amount, String reason) {
        if (!amount.isPositive()) {
            return EconomyResult.failed(EconomyResult.Outcome.INVALID_AMOUNT, amount, balance(player));
        }
        return result(vault.depositPlayer(players.apply(player), VaultAmounts.toDouble(amount, currency())),
                amount, player);
    }

    @Override
    public EconomyResult withdraw(UUID player, Money amount, String reason) {
        if (!amount.isPositive()) {
            return EconomyResult.failed(EconomyResult.Outcome.INVALID_AMOUNT, amount, balance(player));
        }
        if (!has(player, amount)) {
            return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, balance(player));
        }
        return result(vault.withdrawPlayer(players.apply(player), VaultAmounts.toDouble(amount, currency())),
                amount, player);
    }

    @Override
    public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
        EconomyResult taken = withdraw(from, amount, reason);
        if (!taken.succeeded()) {
            return taken;
        }
        EconomyResult given = deposit(to, amount, reason);
        if (!given.succeeded()) {
            deposit(from, amount, "Refund: " + reason);
            return EconomyResult.failed(given.outcome(), amount, balance(from));
        }
        return EconomyResult.done(amount, balance(from));
    }

    private EconomyResult result(EconomyResponse response, Money amount, UUID player) {
        Money after = Optional.ofNullable(response)
                .flatMap(r -> VaultAmounts.toMoney(r.balance, currency(), RoundingMode.DOWN))
                .orElseGet(() -> balance(player));
        if (response != null && response.transactionSuccess()) {
            return EconomyResult.done(amount, after);
        }
        return EconomyResult.failed(EconomyResult.Outcome.REFUSED, amount, after);
    }
}
