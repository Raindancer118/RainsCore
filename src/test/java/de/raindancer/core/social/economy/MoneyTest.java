package de.raindancer.core.social.economy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    @DisplayName("arithmetic is exact and refuses to overflow rather than wrapping into a fortune")
    void arithmetic() {
        Money ten = Money.of(1000);
        assertThat(ten.plus(Money.of(250))).isEqualTo(Money.of(1250));
        assertThat(ten.minus(Money.of(250))).isEqualTo(Money.of(750));
        assertThat(ten.times(3)).isEqualTo(Money.of(3000));
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE).plus(Money.of(1)))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE / 2 + 1).times(2))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("a share is rounded down, so a tax or an interest payment never invents a cent")
    void shares() {
        assertThat(Money.of(999).share(0.10)).isEqualTo(Money.of(99));
        assertThat(Money.of(1).share(0.5)).isEqualTo(Money.ZERO);
        assertThat(Money.of(1000).share(1.5)).isEqualTo(Money.of(1500));
        assertThat(Money.of(1000).share(-1)).isEqualTo(Money.ZERO);
        assertThat(Money.of(1000).share(Double.NaN)).isEqualTo(Money.ZERO);
        assertThat(Money.of(Long.MAX_VALUE).share(2.0)).isEqualTo(Money.of(Long.MAX_VALUE));
    }

    @Test
    @DisplayName("comparisons read the way a sentence does")
    void comparisons() {
        assertThat(Money.of(5).isAtLeast(Money.of(5))).isTrue();
        assertThat(Money.of(4).isAtLeast(Money.of(5))).isFalse();
        assertThat(Money.of(6).isMoreThan(Money.of(5))).isTrue();
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(Money.of(-1).isNegative()).isTrue();
        assertThat(Money.of(3).min(Money.of(2))).isEqualTo(Money.of(2));
        assertThat(Money.of(3).max(Money.of(2))).isEqualTo(Money.of(3));
        assertThat(Money.of(3).compareTo(Money.of(2))).isPositive();
    }
}
