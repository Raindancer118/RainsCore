package de.raindancer.core.platform.permission;

import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * "How many may this player have", when the answer is a permission node with a number on the end.
 *
 * <h2>The bug this exists to make unrepeatable</h2>
 * The obvious way to read {@code homes.limit.<n>} is to ask {@code hasPermission("homes.limit." + n)}
 * for every n and keep the highest that says yes. On Bukkit that is wrong, and wrong in the worst
 * direction: <b>a permission that has never been declared defaults to true for an operator.</b> So
 * every operator "held" {@code homes.limit.100} and was quietly given a hundred homes on a server
 * whose owner had configured three.
 *
 * <p>The fix is to read what has actually been <em>granted</em> — {@link Permissible#getEffectivePermissions()}
 * — and that fix had been written once in the homes plugin and copied once into a host that vendored
 * it. Two copies of a subtle correction is one that gets fixed in one of them, so it lives here.
 *
 * <h2>Why the reading and the deciding are separate</h2>
 * {@link #of} needs a server; {@link #reading} takes plain strings. Everything that decides anything
 * is on this side of that line, so the rule can be asked a hundred ways in a test — which is the only
 * way a mistake in it is ever found, since the wrong answer looks like a working plugin.
 */
public final class NumberedLimit {

    /** What {@link #describe} says instead of {@link Integer#MAX_VALUE}. */
    public static final String NO_LIMIT = "∞";

    /** The highest number granted, or empty when none was. */
    private final Optional<Integer> granted;
    private final boolean unlimited;

    private NumberedLimit(Optional<Integer> granted, boolean unlimited) {
        this.granted = granted;
        this.unlimited = unlimited;
    }

    // ------------------------------------------------------------------------ reading it

    /**
     * What this player has been granted.
     *
     * <p>{@code getEffectivePermissions} rather than {@code hasPermission} — see the class note. This
     * is the only method here that needs a server.
     *
     * @param prefix       the node up to and including the dot, e.g. {@code "homes.limit."}
     * @param unlimitedNode the node that means "no limit at all", or null for none
     */
    public static NumberedLimit of(Permissible who, String prefix, String unlimitedNode) {
        if (who == null) {
            return reading(prefix, Set.of(), unlimitedNode);
        }
        Set<String> granted = new LinkedHashSet<>();
        for (PermissionAttachmentInfo held : who.getEffectivePermissions()) {
            if (held.getValue()) {
                granted.add(held.getPermission());
            }
        }
        // The unlimited node is asked for directly as well: it is declared, so hasPermission is safe
        // for it, and an admin may have been given it through a group whose grants are not listed
        // individually.
        boolean isUnlimited = unlimitedNode != null && !unlimitedNode.isBlank()
                && (granted.contains(unlimitedNode.toLowerCase(Locale.ROOT))
                        || who.hasPermission(unlimitedNode));
        return new NumberedLimit(highestIn(prefix, granted), isUnlimited);
    }

    /** The same, from grants already in hand — what a test uses. */
    public static NumberedLimit reading(String prefix, Set<String> granted) {
        return reading(prefix, granted, null);
    }

    /** The same, with a node that means no limit at all. */
    public static NumberedLimit reading(String prefix, Set<String> granted, String unlimitedNode) {
        Set<String> held = granted == null ? Set.of() : granted;
        boolean isUnlimited = unlimitedNode != null && !unlimitedNode.isBlank()
                && held.contains(unlimitedNode);
        return new NumberedLimit(highestIn(prefix, held), isUnlimited);
    }

    /**
     * The largest number on the end of a node with this prefix.
     *
     * <p>A blank prefix matches nothing rather than everything: matching everything would make the
     * first number anywhere in anybody's permissions the answer, which is the sort of thing only
     * found on somebody else's server.
     */
    private static Optional<Integer> highestIn(String prefix, Set<String> granted) {
        if (prefix == null || prefix.isBlank()) {
            return Optional.empty();
        }
        Integer highest = null;
        for (String node : granted) {
            if (node == null || !node.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                continue;
            }
            String tail = node.substring(prefix.length());
            try {
                int number = Integer.parseInt(tail);
                // Negative is not a number of things anybody may have.
                if (number >= 0 && (highest == null || number > highest)) {
                    highest = number;
                }
            } catch (NumberFormatException notANumber) {
                // Somebody wrote `homes.limit.lots`, or pasted a number longer than an int. Ignored
                // rather than thrown: refusing to answer at all would take the feature down over one
                // line in a permissions file.
            }
        }
        return Optional.ofNullable(highest);
    }

    // ------------------------------------------------------------------------ asking it

    /** Whether this player has no limit at all. */
    public boolean isUnlimited() {
        return unlimited;
    }

    /**
     * The limit, given what the config says.
     *
     * <p>A node can only ever <em>raise</em> the configured number. Lowering it would mean granting
     * somebody a permission takes something away from them, which is the opposite of what granting a
     * permission means to anybody reading a permissions file.
     */
    public int highestOf(int configured) {
        if (unlimited) {
            return Integer.MAX_VALUE;
        }
        int floor = Math.max(0, configured);
        return granted.map(number -> Math.max(floor, number)).orElse(floor);
    }

    /** Whether there is room for one more. */
    public boolean isRoomFor(int howManyThereAre, int configured) {
        return howManyThereAre < highestOf(configured);
    }

    /**
     * The limit as a player should read it.
     *
     * <p>{@link #NO_LIMIT} rather than {@link Integer#MAX_VALUE}: "2147483647" on somebody's screen
     * is a bug they will report.
     */
    public String describe(int configured) {
        return unlimited ? NO_LIMIT : String.valueOf(highestOf(configured));
    }
}
