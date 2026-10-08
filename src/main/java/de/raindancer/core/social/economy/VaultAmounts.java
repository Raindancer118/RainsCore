package de.raindancer.core.social.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Vault speaks in {@code double}s; Rain in minor units. The conversion, once.
 *
 * <p>Through {@link BigDecimal#valueOf(double)}, which uses the shortest decimal that round-trips:
 * {@code 0.1 + 0.2} arrives as {@code 0.30000000000000004} and is thirty cents, not twenty-nine.
 */
final class VaultAmounts {

    private VaultAmounts() {
    }

    /**
     * @param rounding {@code DOWN} for money arriving, {@code UP} for money leaving — never in the
     *                 caller's favour by a fraction of a cent
     */
    static Optional<Money> toMoney(double amount, Currency currency, RoundingMode rounding) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0) {
            return Optional.empty();
        }
        BigDecimal minor = BigDecimal.valueOf(amount).movePointRight(currency.decimals())
                .setScale(0, rounding);
        if (minor.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return Optional.empty();
        }
        return Optional.of(Money.of(minor.longValueExact()));
    }

    static double toDouble(Money money, Currency currency) {
        return BigDecimal.valueOf(money.minor()).movePointLeft(currency.decimals()).doubleValue();
    }
}
