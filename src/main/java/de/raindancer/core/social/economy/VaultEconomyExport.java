package de.raindancer.core.social.economy;

import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;

import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * A Rain {@link Economy}, as every Vault plugin expects to find one. World arguments are ignored:
 * one server, one set of balances.
 */
final class VaultEconomyExport implements net.milkbowl.vault.economy.Economy {

    private final Economy economy;
    private final Function<String, Optional<UUID>> byName;

    VaultEconomyExport(Economy economy, Function<String, Optional<UUID>> byName) {
        this.economy = economy;
        this.byName = byName;
    }

    Economy economy() {
        return economy;
    }

    private Currency currency() {
        return economy.currency();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return economy.name();
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return currency().decimals();
    }

    @Override
    public String format(double amount) {
        boolean negative = amount < 0;
        Money money = VaultAmounts.toMoney(Math.abs(amount), currency(), RoundingMode.HALF_UP).orElse(Money.ZERO);
        return currency().format(negative ? money.negate() : money);
    }

    @Override
    public String currencyNamePlural() {
        return currency().plural();
    }

    @Override
    public String currencyNameSingular() {
        return currency().singular();
    }

    // ---------------------------------------------------------------------------- accounts

    @Override
    public boolean hasAccount(String playerName) {
        return byName.apply(playerName).map(economy::hasAccount).orElse(false);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return player != null && economy.hasAccount(player.getUniqueId());
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return byName.apply(playerName).map(economy::createAccount).orElse(false);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return player != null && economy.createAccount(player.getUniqueId());
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // ---------------------------------------------------------------------------- balances

    @Override
    public double getBalance(String playerName) {
        return byName.apply(playerName).map(this::balanceOf).orElse(0.0);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return player == null ? 0.0 : balanceOf(player.getUniqueId());
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    private double balanceOf(UUID player) {
        return VaultAmounts.toDouble(economy.balance(player), currency());
    }

    @Override
    public boolean has(String playerName, double amount) {
        return byName.apply(playerName).map(id -> hasOn(id, amount)).orElse(false);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return player != null && hasOn(player.getUniqueId(), amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    private boolean hasOn(UUID player, double amount) {
        return VaultAmounts.toMoney(amount, currency(), RoundingMode.UP)
                .map(money -> economy.has(player, money)).orElse(false);
    }

    // ---------------------------------------------------------------------------- moving

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return byName.apply(playerName).map(id -> withdraw(id, amount)).orElseGet(() -> noAccount(amount));
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return player == null ? noAccount(amount) : withdraw(player.getUniqueId(), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return byName.apply(playerName).map(id -> deposit(id, amount)).orElseGet(() -> noAccount(amount));
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return player == null ? noAccount(amount) : deposit(player.getUniqueId(), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    private EconomyResponse withdraw(UUID player, double amount) {
        Optional<Money> money = VaultAmounts.toMoney(amount, currency(), RoundingMode.UP);
        if (money.isEmpty()) {
            return invalid(amount, player);
        }
        return response(economy.withdraw(player, money.get(), "Vault withdrawal"));
    }

    private EconomyResponse deposit(UUID player, double amount) {
        Optional<Money> money = VaultAmounts.toMoney(amount, currency(), RoundingMode.DOWN);
        if (money.isEmpty()) {
            return invalid(amount, player);
        }
        return response(economy.deposit(player, money.get(), "Vault deposit"));
    }

    private EconomyResponse response(EconomyResult result) {
        double amount = VaultAmounts.toDouble(result.amount(), currency());
        double balance = VaultAmounts.toDouble(result.balance(), currency());
        return result.succeeded()
                ? new EconomyResponse(amount, balance, ResponseType.SUCCESS, null)
                : new EconomyResponse(amount, balance, ResponseType.FAILURE, describe(result.outcome()));
    }

    private EconomyResponse invalid(double amount, UUID player) {
        return new EconomyResponse(amount, balanceOf(player), ResponseType.FAILURE,
                "That is not an amount of money.");
    }

    private static EconomyResponse noAccount(double amount) {
        return new EconomyResponse(amount, 0, ResponseType.FAILURE, "There is no such account.");
    }

    static String describe(EconomyResult.Outcome outcome) {
        return switch (outcome) {
            case DONE -> "";
            case NOT_ENOUGH -> "Insufficient funds.";
            case TOO_MUCH -> "That would take the account past the most it may hold.";
            case NO_ACCOUNT -> "There is no such account.";
            case FROZEN -> "The account is frozen.";
            case INVALID_AMOUNT -> "That is not an amount of money.";
            case UNAVAILABLE -> "The economy is not available right now.";
            case REFUSED -> "Refused.";
            case TREASURY_EMPTY -> "The server's treasury cannot pay that right now.";
        };
    }

    // ---------------------------------------------------------------------------- banks

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "Banks are not supported.");
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return noBanks();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}
