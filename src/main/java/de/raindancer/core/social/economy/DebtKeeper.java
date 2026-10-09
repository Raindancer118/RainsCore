package de.raindancer.core.social.economy;

import java.util.UUID;

/** Keeps what somebody owes the server — an unpaid fine, missed upkeep — so the economy can collect it. */
public interface DebtKeeper {

    /** What {@code who} owes this keeper; zero for nothing. */
    Money owed(UUID who);

    /**
     * {@code amount} has been taken from {@code who} toward their debt here and has already left their account.
     *
     * @return how much of it this keeper settled — never more than was owed
     */
    Money paid(UUID who, Money amount);
}
