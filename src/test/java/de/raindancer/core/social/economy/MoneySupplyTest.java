package de.raindancer.core.social.economy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoneySupplyTest {

    @Test
    @DisplayName("an open economy has no treasury: it prints what it pays")
    void open() {
        MoneySupply open = MoneySupply.open(Money.of(5000), 5);
        assertThat(open.capped()).isFalse();
        assertThat(open.treasury()).isEqualTo(Money.ZERO);
        assertThat(open.fill()).isEqualTo(1.0);
        assertThat(open.perActivePlayer()).isEqualTo(Money.of(1000));
    }

    @Test
    @DisplayName("under a cap, the treasury is whatever of the cap nobody holds")
    void capped() {
        MoneySupply capped = MoneySupply.capped(Money.of(10_000), Money.of(7_500), 0);
        assertThat(capped.treasury()).isEqualTo(Money.of(2_500));
        assertThat(capped.fill()).isEqualTo(0.25);
        assertThat(capped.perActivePlayer()).isEqualTo(Money.of(7_500));
    }

    @Test
    @DisplayName("more in circulation than the cap allows is an empty treasury, never a negative one")
    void overCap() {
        MoneySupply over = MoneySupply.capped(Money.of(100), Money.of(150), 1);
        assertThat(over.treasury()).isEqualTo(Money.ZERO);
        assertThat(over.fill()).isZero();
        assertThat(over.overCap()).isEqualTo(Money.of(50));
    }
}
