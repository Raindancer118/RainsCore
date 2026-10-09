package de.raindancer.core.social.economy;

/**
 * What came of moving money.
 *
 * @param amount  what was asked to move
 * @param balance the account's balance afterwards — for a refusal, as it still is
 */
public record EconomyResult(Outcome outcome, Money amount, Money balance) {

    public enum Outcome {
        DONE,
        /** They do not have that much. */
        NOT_ENOUGH,
        /** It would take the receiver past the most an account may hold. */
        TOO_MUCH,
        NO_ACCOUNT,
        /** Staff have frozen the account. */
        FROZEN,
        /** Zero, negative, or not a number anybody can hold. */
        INVALID_AMOUNT,
        /** The provider cannot answer right now — its storage is down, or it is shutting down. */
        UNAVAILABLE,
        /** Refused for a reason of the provider's own; see its log. */
        REFUSED,
        /** The server has a hard cap on money and its treasury cannot pay this out right now. */
        TREASURY_EMPTY
    }

    public EconomyResult {
        amount = amount == null ? Money.ZERO : amount;
        balance = balance == null ? Money.ZERO : balance;
    }

    public static EconomyResult done(Money amount, Money balance) {
        return new EconomyResult(Outcome.DONE, amount, balance);
    }

    public static EconomyResult failed(Outcome outcome, Money amount, Money balance) {
        return new EconomyResult(outcome == Outcome.DONE ? Outcome.REFUSED : outcome, amount, balance);
    }

    public boolean succeeded() {
        return outcome == Outcome.DONE;
    }
}
