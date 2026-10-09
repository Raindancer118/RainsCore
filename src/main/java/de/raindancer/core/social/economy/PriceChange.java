package de.raindancer.core.social.economy;

/**
 * How much one player's price differs from everybody's, and why.
 *
 * @param percent added to the price: {@code -25} is a quarter off, {@code 10} a tenth more; clamped to
 *                {@code -100..1000}
 * @param reason  what the player is shown next to the price, such as a role's name
 */
public record PriceChange(int percent, String reason) {

    public PriceChange {
        percent = Math.clamp(percent, -100, 1000);
        reason = reason == null ? "" : reason;
    }
}
