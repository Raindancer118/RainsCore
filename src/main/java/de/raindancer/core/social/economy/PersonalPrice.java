package de.raindancer.core.social.economy;

import java.util.List;

/**
 * One player's price for something, next to everybody's.
 *
 * @param percent the change that was applied, after clamping
 * @param reasons why, in the order the modifiers were provided; empty when nothing changed
 */
public record PersonalPrice(Money base, Money price, int percent, List<String> reasons) {

    public PersonalPrice {
        reasons = List.copyOf(reasons);
    }

    public static PersonalPrice unchanged(Money base) {
        return new PersonalPrice(base, base, 0, List.of());
    }

    public boolean changed() {
        return !price.equals(base);
    }
}
