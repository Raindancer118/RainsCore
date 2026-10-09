package de.raindancer.core.social.economy;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EconomyLeversTest {

    private final Plugin owner = mock(Plugin.class);

    @AfterEach
    void reset() {
        EconomyLevers.clear();
    }

    @Test
    @DisplayName("with no lever pulled, what is paid and what is charged are exactly as asked")
    void untouched() {
        assertThat(EconomyLevers.faucet("reward.mob", Money.of(1234))).isEqualTo(Money.of(1234));
        assertThat(EconomyLevers.sink("claims.upkeep", Money.of(1234))).isEqualTo(Money.of(1234));
        assertThat(EconomyLevers.faucetPercent("reward.mob")).isZero();
    }

    @Test
    @DisplayName("levers add up, payouts round down and charges round up, so nothing hands out a cent")
    void scaling() {
        EconomyLevers.provide(owner, new EconomyLever() {
            @Override
            public int faucetChange(String source) {
                return source.startsWith("reward.") ? -25 : 0;
            }

            @Override
            public int sinkChange(String source) {
                return 10;
            }
        });
        EconomyLevers.provide(owner, new EconomyLever() {
            @Override
            public int faucetChange(String source) {
                return -10;
            }
        });
        assertThat(EconomyLevers.faucetPercent("reward.mob")).isEqualTo(-35);
        assertThat(EconomyLevers.faucet("reward.mob", Money.of(101))).isEqualTo(Money.of(65));
        assertThat(EconomyLevers.faucet("income", Money.of(101))).isEqualTo(Money.of(90));
        assertThat(EconomyLevers.sink("fee", Money.of(101))).isEqualTo(Money.of(112));
    }

    @Test
    @DisplayName("a payout can be switched off entirely but never turned negative; a charge never drops to nothing")
    void bounds() {
        EconomyLevers.provide(owner, new EconomyLever() {
            @Override
            public int faucetChange(String source) {
                return -500;
            }

            @Override
            public int sinkChange(String source) {
                return -500;
            }
        });
        assertThat(EconomyLevers.faucet("x", Money.of(100))).isEqualTo(Money.ZERO);
        assertThat(EconomyLevers.sink("x", Money.of(100))).isEqualTo(Money.of(10));
    }

    @Test
    @DisplayName("a lever that throws is left out rather than stopping the payment")
    void broken() {
        EconomyLevers.provide(owner, new EconomyLever() {
            @Override
            public int faucetChange(String source) {
                throw new IllegalStateException("boom");
            }
        });
        assertThat(EconomyLevers.faucet("x", Money.of(100))).isEqualTo(Money.of(100));
    }

    @Test
    @DisplayName("retracting a lever puts things back; providing it twice is one lever")
    void lifecycle() {
        EconomyLever half = new EconomyLever() {
            @Override
            public int faucetChange(String source) {
                return -50;
            }
        };
        EconomyLevers.provide(owner, half);
        EconomyLevers.provide(owner, half);
        assertThat(EconomyLevers.faucet("x", Money.of(100))).isEqualTo(Money.of(50));
        EconomyLevers.retract(half);
        assertThat(EconomyLevers.faucet("x", Money.of(100))).isEqualTo(Money.of(100));
    }
}
