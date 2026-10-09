package de.raindancer.core.social.economy;

/**
 * How much money there is, and — on a server with a hard cap — how much the treasury still holds.
 *
 * <p>Under a cap nothing is printed: every payout comes out of the treasury and every fee, tax and fine goes
 * back into it, so the treasury is simply whatever of the cap nobody holds. An open economy prints what it
 * pays and has no treasury to speak of.
 *
 * @param circulating   every balance, every banknote and coin out there, every pot and escrow
 * @param cap           the most there may ever be; {@link Money#ZERO} for an open economy
 * @param activePlayers players seen lately, for "how much does a player have, on average"
 */
public record MoneySupply(boolean capped, Money cap, Money circulating, int activePlayers) {

    public MoneySupply {
        cap = cap == null || !capped ? Money.ZERO : cap;
        circulating = circulating == null ? Money.ZERO : circulating;
        activePlayers = Math.max(0, activePlayers);
    }

    public static MoneySupply open(Money circulating, int activePlayers) {
        return new MoneySupply(false, Money.ZERO, circulating, activePlayers);
    }

    public static MoneySupply capped(Money cap, Money circulating, int activePlayers) {
        return new MoneySupply(true, cap, circulating, activePlayers);
    }

    /** What the treasury can still pay out; zero for an open economy and for one over its cap. */
    public Money treasury() {
        return capped ? cap.minus(circulating).max(Money.ZERO) : Money.ZERO;
    }

    /** How much more is out there than the cap allows — a bug, a dupe, or a cap lowered under the money. */
    public Money overCap() {
        return capped ? circulating.minus(cap).max(Money.ZERO) : Money.ZERO;
    }

    /** The treasury as a share of the cap, {@code 0..1}; always {@code 1} for an open economy. */
    public double fill() {
        if (!capped) {
            return 1.0;
        }
        return cap.isPositive() ? (double) treasury().minor() / cap.minor() : 0.0;
    }

    /** What an active player holds on average; everything when nobody has been seen lately. */
    public Money perActivePlayer() {
        return activePlayers == 0 ? circulating : Money.of(circulating.minor() / activePlayers);
    }
}
