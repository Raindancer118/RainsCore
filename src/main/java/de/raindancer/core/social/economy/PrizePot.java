package de.raindancer.core.social.economy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is paid what out of a pot of entry fees — a hunger games round, a speedrun race, any game with a buy-in.
 * Nothing is paid that was not taken: the house cut is rounded down, every share is rounded down, and the
 * rounding dust goes to first place, so what is paid out is the pot minus the cut to the unit. Who came which
 * place is the game's business; this only shares the money.
 */
public final class PrizePot {

    /** The part of the pot the server keeps; the percent is clamped to 0–100, the result rounded down. */
    public Money houseCut(Money pot, int percent) {
        if (!pot.isPositive()) {
            return Money.ZERO;
        }
        BigInteger cut = BigInteger.valueOf(pot.minor()).multiply(BigInteger.valueOf(Math.clamp(percent, 0, 100)))
                .divide(BigInteger.valueOf(100));
        return Money.of(cut.longValueExact());
    }

    /**
     * @param places first place first; each place is the players who share it evenly (a team, or one player)
     * @param split  weights per place, "100" or "60,30,10"; unreadable falls back to winner takes all
     * @return what each player is paid, in place order
     */
    public Map<UUID, Money> payouts(Money pot, int housePercent, String split, List<List<UUID>> places) {
        Map<UUID, Money> paid = new LinkedHashMap<>();
        Money net = pot.minus(houseCut(pot, housePercent));
        List<List<UUID>> filled = places.stream().filter(place -> !place.isEmpty()).toList();
        if (!net.isPositive() || filled.isEmpty()) {
            return paid;
        }
        List<Long> weights = weights(split);
        if (weights.size() > filled.size()) {
            weights = weights.subList(0, filled.size());
        }
        long sum = weights.stream().mapToLong(Long::longValue).sum();
        if (sum <= 0) {
            weights = List.of(100L);
            sum = 100;
        }
        long[] shares = new long[weights.size()];
        long given = 0;
        for (int i = 0; i < shares.length; i++) {
            shares[i] = BigInteger.valueOf(net.minor()).multiply(BigInteger.valueOf(weights.get(i)))
                    .divide(BigInteger.valueOf(sum)).longValueExact();
            given += shares[i];
        }
        shares[0] += net.minor() - given;
        for (int i = 0; i < shares.length; i++) {
            List<UUID> place = filled.get(i);
            long each = shares[i] / place.size();
            long odd = shares[i] % place.size();
            for (int member = 0; member < place.size(); member++) {
                long amount = each + (member < odd ? 1 : 0);
                if (amount > 0) {
                    paid.merge(place.get(member), Money.of(amount), Money::plus);
                }
            }
        }
        return paid;
    }

    private static List<Long> weights(String split) {
        List<Long> weights = new ArrayList<>();
        if (split != null) {
            for (String part : split.split(",")) {
                try {
                    long weight = Long.parseLong(part.strip());
                    if (weight >= 0) {
                        weights.add(weight);
                    }
                } catch (NumberFormatException unreadable) {
                    // a part nobody can read is skipped; what is left still decides the split
                }
            }
        }
        return weights.isEmpty() ? List.of(100L) : weights;
    }
}
