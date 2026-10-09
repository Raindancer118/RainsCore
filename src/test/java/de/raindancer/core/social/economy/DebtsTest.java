package de.raindancer.core.social.economy;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DebtsTest {

    private final Plugin owner = mock(Plugin.class);
    private final UUID player = UUID.randomUUID();

    @AfterEach
    void reset() {
        Debts.clear();
    }

    @Test
    @DisplayName("nobody owes anything while nothing keeps debts")
    void nobody() {
        assertThat(Debts.owed(player)).isEqualTo(Money.ZERO);
        assertThat(Debts.inDebt(player)).isFalse();
        assertThat(Debts.collected(player, Money.of(50))).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("what is owed adds up across keepers, and a payment settles the oldest keeper first")
    void settling() {
        Ledger fines = new Ledger(Map.of(player, Money.of(100)));
        Ledger upkeep = new Ledger(Map.of(player, Money.of(30)));
        Debts.provide(owner, fines);
        Debts.provide(owner, upkeep);
        assertThat(Debts.owed(player)).isEqualTo(Money.of(130));
        assertThat(Debts.inDebt(player)).isTrue();

        assertThat(Debts.collected(player, Money.of(120))).isEqualTo(Money.of(120));
        assertThat(fines.owed(player)).isEqualTo(Money.ZERO);
        assertThat(upkeep.owed(player)).isEqualTo(Money.of(10));

        assertThat(Debts.collected(player, Money.of(500))).as("never more than is owed").isEqualTo(Money.of(10));
    }

    @Test
    @DisplayName("a keeper that throws is skipped, never blocks the payment")
    void broken() {
        Debts.provide(owner, new DebtKeeper() {
            @Override
            public Money owed(UUID who) {
                throw new IllegalStateException();
            }

            @Override
            public Money paid(UUID who, Money amount) {
                throw new IllegalStateException();
            }
        });
        assertThat(Debts.owed(player)).isEqualTo(Money.ZERO);
        assertThat(Debts.collected(player, Money.of(5))).isEqualTo(Money.ZERO);
    }

    private static final class Ledger implements DebtKeeper {
        private final Map<UUID, Money> owed;

        Ledger(Map<UUID, Money> owed) {
            this.owed = new HashMap<>(owed);
        }

        @Override
        public Money owed(UUID who) {
            return owed.getOrDefault(who, Money.ZERO);
        }

        @Override
        public Money paid(UUID who, Money amount) {
            Money taken = amount.min(owed(who));
            owed.put(who, owed(who).minus(taken));
            return taken;
        }
    }
}
