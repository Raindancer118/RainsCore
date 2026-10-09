package de.raindancer.core.social.economy;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What players owe the server, wherever it is kept: a moderation plugin's unpaid fines, a claim's missed
 * upkeep. The economy asks here before paying somebody out and takes a share toward it; a game of chance asks
 * whether somebody is in debt at all.
 *
 * <p>With nothing keeping debts nobody owes anything, which is every server that never fines anybody.
 */
public final class Debts {

    private static final LogChannel log = Log.of("economy");

    private record Provided(Plugin owner, DebtKeeper keeper) {
    }

    private static final List<Provided> providers = new CopyOnWriteArrayList<>();

    private Debts() {
    }

    public static synchronized void provide(Plugin owner, DebtKeeper keeper) {
        if (keeper != null && providers.stream().noneMatch(each -> each.keeper() == keeper)) {
            providers.add(new Provided(owner, keeper));
        }
    }

    public static synchronized void retract(DebtKeeper keeper) {
        providers.removeIf(each -> each.keeper() == keeper);
    }

    /** Everything {@code who} owes, across every keeper. */
    public static Money owed(UUID who) {
        Money total = Money.ZERO;
        for (Provided each : providers) {
            try {
                Money owed = each.keeper().owed(who);
                if (owed != null && owed.isPositive()) {
                    total = total.plus(owed);
                }
            } catch (RuntimeException broken) {
                log.error(broken, "A debt keeper could not say what {} owes; it is left out.", who);
            }
        }
        return total;
    }

    public static boolean inDebt(UUID who) {
        return !providers.isEmpty() && owed(who).isPositive();
    }

    /**
     * Hands {@code amount}, already taken from {@code who}, to the keepers in the order they were provided.
     *
     * @return how much of it settled debts; the caller gives back anything beyond that
     */
    public static Money collected(UUID who, Money amount) {
        Money left = amount == null ? Money.ZERO : amount;
        Money settled = Money.ZERO;
        for (Provided each : providers) {
            if (!left.isPositive()) {
                break;
            }
            try {
                Money owed = each.keeper().owed(who);
                if (owed == null || !owed.isPositive()) {
                    continue;
                }
                Money taken = each.keeper().paid(who, left.min(owed));
                taken = taken == null ? Money.ZERO : taken.max(Money.ZERO).min(left);
                left = left.minus(taken);
                settled = settled.plus(taken);
            } catch (RuntimeException broken) {
                log.error(broken, "A debt keeper could not take a payment from {}; it is skipped.", who);
            }
        }
        return settled;
    }

    public static synchronized int forgetFrom(ClassLoader loader) {
        int before = providers.size();
        providers.removeIf(each -> PluginCode.isFrom(each.keeper(), loader));
        return before - providers.size();
    }

    public static synchronized void clear() {
        providers.clear();
    }
}
