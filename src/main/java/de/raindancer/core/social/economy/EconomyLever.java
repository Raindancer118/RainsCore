package de.raindancer.core.social.economy;

/**
 * Turns money coming into the economy, or money leaving it, up or down — what an inflation stabiliser, a
 * treasury running low or an event pulls on.
 *
 * <p>A <em>source</em> is a short dotted key for where the money comes from or goes: {@code reward.mob},
 * {@code income}, {@code jobs.goal}, {@code claims.upkeep}, {@code tpa.fee}. A lever may answer per source
 * or the same for all. Asked for every payment, so it must be a lookup, not work.
 */
public interface EconomyLever {

    /** Percent added to a payout from {@code source}: {@code -25} pays three quarters. */
    default int faucetChange(String source) {
        return 0;
    }

    /** Percent added to a fee or tax going to {@code source}: {@code 10} charges a tenth more. */
    default int sinkChange(String source) {
        return 0;
    }
}
