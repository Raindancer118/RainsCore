package de.raindancer.core.social.economy;

import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Both directions of the Vault bridge, without a server: a Rain economy as Vault plugins see it, and a
 * Vault economy as Rain plugins see it.
 */
class VaultBridgeTest {

    private final UUID alice = UUID.randomUUID();
    private final Ledger ledger = new Ledger();
    private final VaultEconomyExport exported = new VaultEconomyExport(ledger,
            name -> name.equalsIgnoreCase("alice") ? Optional.of(alice) : Optional.empty());

    private OfflinePlayer player(UUID id) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Test
    @DisplayName("doubles become minor units: deposits round down, withdrawals round up, never in the player's favour")
    void amounts() {
        Currency cents = Currency.DEFAULT.withDecimals(2);
        assertThat(VaultAmounts.toMoney(10.0, cents, RoundingMode.DOWN)).contains(Money.of(1000));
        assertThat(VaultAmounts.toMoney(0.005, cents, RoundingMode.DOWN)).contains(Money.ZERO);
        assertThat(VaultAmounts.toMoney(0.005, cents, RoundingMode.UP)).contains(Money.of(1));
        assertThat(VaultAmounts.toMoney(0.1 + 0.2, cents, RoundingMode.DOWN)).contains(Money.of(30));
        assertThat(VaultAmounts.toMoney(-1, cents, RoundingMode.DOWN)).isEmpty();
        assertThat(VaultAmounts.toMoney(Double.NaN, cents, RoundingMode.DOWN)).isEmpty();
        assertThat(VaultAmounts.toMoney(Double.POSITIVE_INFINITY, cents, RoundingMode.DOWN)).isEmpty();
        assertThat(VaultAmounts.toMoney(1e30, cents, RoundingMode.DOWN)).isEmpty();
        assertThat(VaultAmounts.toDouble(Money.of(1234), cents)).isEqualTo(12.34);
    }

    @Test
    @DisplayName("a Vault plugin depositing and withdrawing moves the Rain balance")
    void exportMovesMoney() {
        EconomyResponse in = exported.depositPlayer(player(alice), 12.5);
        assertThat(in.transactionSuccess()).isTrue();
        assertThat(ledger.balance(alice)).isEqualTo(Money.of(1250));
        assertThat(exported.getBalance(player(alice))).isEqualTo(12.5);

        EconomyResponse out = exported.withdrawPlayer(player(alice), 20);
        assertThat(out.transactionSuccess()).isFalse();
        assertThat(out.type).isEqualTo(EconomyResponse.ResponseType.FAILURE);
        assertThat(ledger.balance(alice)).isEqualTo(Money.of(1250));

        assertThat(exported.withdrawPlayer(player(alice), 2.5).balance).isEqualTo(10.0);
        assertThat(exported.has(player(alice), 10.0)).isTrue();
        assertThat(exported.has(player(alice), 10.01)).isFalse();
    }

    @Test
    @DisplayName("the old name-based calls find the player by name, and an unknown name has no account")
    void names() {
        assertThat(exported.depositPlayer("Alice", 1).transactionSuccess()).isTrue();
        assertThat(exported.getBalance("alice")).isEqualTo(1.0);
        assertThat(exported.hasAccount("nobody")).isFalse();
        assertThat(exported.depositPlayer("nobody", 1).transactionSuccess()).isFalse();
    }

    @Test
    @DisplayName("banks are not offered, and say so rather than pretending")
    void banks() {
        assertThat(exported.hasBankSupport()).isFalse();
        assertThat(exported.createBank("b", "alice").type)
                .isEqualTo(EconomyResponse.ResponseType.NOT_IMPLEMENTED);
        assertThat(exported.getBanks()).isEmpty();
    }

    @Test
    @DisplayName("Vault sees the Rain currency's names, digits and formatting")
    void currency() {
        assertThat(exported.currencyNameSingular()).isEqualTo("Coin");
        assertThat(exported.currencyNamePlural()).isEqualTo("Coins");
        assertThat(exported.fractionalDigits()).isEqualTo(2);
        assertThat(exported.format(1234.5)).isEqualTo(Currency.DEFAULT.format(Money.of(123450)));
        assertThat(exported.getName()).isEqualTo("Ledger");
    }

    @Test
    @DisplayName("a foreign Vault economy, seen from Rain's side, moves money and refuses honestly")
    void importMovesMoney() {
        VaultEconomyImport imported = new VaultEconomyImport(exported, id -> player(id));
        assertThat(imported.name()).isEqualTo("Vault: Ledger");
        assertThat(imported.currency().decimals()).isEqualTo(2);

        assertThat(imported.deposit(alice, Money.of(500), "test").succeeded()).isTrue();
        assertThat(imported.balance(alice)).isEqualTo(Money.of(500));
        assertThat(imported.withdraw(alice, Money.of(900), "test").outcome())
                .isEqualTo(EconomyResult.Outcome.NOT_ENOUGH);

        UUID bob = UUID.randomUUID();
        assertThat(imported.transfer(alice, bob, Money.of(200), "test").succeeded()).isTrue();
        assertThat(ledger.balance(alice)).isEqualTo(Money.of(300));
        assertThat(ledger.balance(bob)).isEqualTo(Money.of(200));
        assertThat(imported.transfer(alice, bob, Money.of(5000), "test").succeeded()).isFalse();
        assertThat(ledger.balance(alice)).as("a refused transfer took nothing").isEqualTo(Money.of(300));
    }

    /** The smallest honest economy. */
    private static final class Ledger implements Economy {
        private final Map<UUID, Money> balances = new HashMap<>();

        @Override
        public String name() {
            return "Ledger";
        }

        @Override
        public Currency currency() {
            return Currency.DEFAULT;
        }

        @Override
        public boolean hasAccount(UUID player) {
            return true;
        }

        @Override
        public synchronized Money balance(UUID player) {
            return balances.getOrDefault(player, Money.ZERO);
        }

        @Override
        public synchronized EconomyResult deposit(UUID player, Money amount, String reason) {
            if (!amount.isPositive()) {
                return EconomyResult.failed(EconomyResult.Outcome.INVALID_AMOUNT, amount, balance(player));
            }
            balances.put(player, balance(player).plus(amount));
            return EconomyResult.done(amount, balance(player));
        }

        @Override
        public synchronized EconomyResult withdraw(UUID player, Money amount, String reason) {
            if (!balance(player).isAtLeast(amount)) {
                return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, balance(player));
            }
            balances.put(player, balance(player).minus(amount));
            return EconomyResult.done(amount, balance(player));
        }

        @Override
        public synchronized EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            EconomyResult taken = withdraw(from, amount, reason);
            if (!taken.succeeded()) {
                return taken;
            }
            deposit(to, amount, reason);
            return EconomyResult.done(amount, balance(from));
        }
    }
}
