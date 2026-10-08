package de.raindancer.core.social.economy;

/**
 * An amount of money in the currency's smallest unit — cents, not dollars.
 *
 * <p>A {@code long} of minor units rather than a {@code double}: {@code 0.1 + 0.2} is not {@code 0.3}
 * in floating point, and an economy that rounds a little on every transfer is one where money appears
 * out of nowhere or quietly drains away. How many minor units make a major one is the
 * {@link Currency}'s business, so the same {@code Money} reads correctly whatever an owner set the
 * decimals to.
 *
 * <p>Arithmetic throws {@link ArithmeticException} on overflow instead of wrapping: a wrapped long is a
 * negative balance that turns into a fortune on the next subtraction.
 */
public record Money(long minor) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public static Money of(long minor) {
        return minor == 0 ? ZERO : new Money(minor);
    }

    public Money plus(Money other) {
        return of(Math.addExact(minor, other.minor));
    }

    public Money minus(Money other) {
        return of(Math.subtractExact(minor, other.minor));
    }

    public Money times(long factor) {
        return of(Math.multiplyExact(minor, factor));
    }

    public Money negate() {
        return of(Math.negateExact(minor));
    }

    /**
     * This much of it, rounded down — a tax, an interest payment, a sell-back price.
     *
     * <p>Down, always: rounding up would hand out a cent nobody paid in, a thousand times a day.
     * Anything that is not a sensible fraction (negative, NaN) is nothing.
     */
    public Money share(double fraction) {
        if (!(fraction > 0) || Double.isInfinite(fraction)) {
            return ZERO;
        }
        double exact = Math.floor(minor * fraction);
        if (exact >= Long.MAX_VALUE) {
            return of(Long.MAX_VALUE);
        }
        if (exact <= Long.MIN_VALUE) {
            return of(Long.MIN_VALUE);
        }
        return of((long) exact);
    }

    public boolean isZero() {
        return minor == 0;
    }

    public boolean isNegative() {
        return minor < 0;
    }

    public boolean isPositive() {
        return minor > 0;
    }

    public boolean isAtLeast(Money other) {
        return minor >= other.minor;
    }

    public boolean isMoreThan(Money other) {
        return minor > other.minor;
    }

    public Money min(Money other) {
        return minor <= other.minor ? this : other;
    }

    public Money max(Money other) {
        return minor >= other.minor ? this : other;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(minor, other.minor);
    }
}
