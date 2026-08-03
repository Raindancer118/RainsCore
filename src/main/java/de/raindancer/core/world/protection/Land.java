package de.raindancer.core.world.protection;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * "May this player do that here?" — asked by anybody, answered by whoever owns the ground.
 *
 * <h2>What Core owns and what it does not</h2>
 * Core owns the <em>question</em>: the vocabulary ({@link LandAction}, {@link LandFlag},
 * {@link LandAudience}), the server's policy over flags, the enforcement listeners, and the throttled refusal
 * a player sees. It owns none of the data. Which piece of ground is where, who is trusted on it and what it is
 * called all come from a {@link LandProvider} that another plugin registers.
 *
 * <p>That split corrects an earlier design in which Core held a whole claim model — shape, members, bans,
 * pantry, bank, fence, entry fee. Those are what a claim <em>is</em>, and a foundation that knows them cannot
 * host anything that is not a claim.
 *
 * <h2>Why every plugin should ask</h2>
 * Because more than one thing moves players and changes blocks: a warp pointing into somebody's house, a
 * teleport request accepted across a border, a ghast line landing in a stranger's garden, a farm world about
 * to be regenerated with builds in it. Each of those has to ask, and before this each either asked nobody or
 * wrote its own rules.
 *
 * <h2>Not knowing is an answer</h2>
 * With no provider registered every question answers {@link LandVerdict#UNKNOWN} rather than "allowed". The
 * difference decides whether an uninstalled module means "nothing is protected, go ahead and delete the
 * world" or "I cannot tell, so don't". See {@link LandVerdict}.
 */
public final class Land {

    private static final LogChannel log = Log.of("land");

    /**
     * The permission that makes the bypass toggle mean anything.
     *
     * <p>Still {@code rec.bypass} rather than something under {@code rainscore.}: this is what is in every
     * existing server's permission plugin, and renaming it would silently take the bypass away from the admins
     * who have it. {@link #BYPASS_PERMISSION_CORE} is accepted too, for a new server that would rather spell it
     * the way the rest of Core does.
     */
    public static final String BYPASS_PERMISSION = "rec.bypass";

    public static final String BYPASS_PERMISSION_CORE = "rainscore.land.bypass";

    /** Server-wide land administration. Kept under the old name for the same reason as the bypass. */
    public static final String ADMIN_PERMISSION = "rec.admin";

    public static final String ADMIN_PERMISSION_CORE = "rainscore.land.admin";

    /**
     * How long a refused player is left alone before being told again.
     *
     * <p>Somebody holding down the left mouse button at a border produces one refusal per tick. Twenty
     * identical lines a second is not a message, it is a denial of service on their own screen.
     */
    private static final long QUIET_AFTER_REFUSAL_MILLIS = 1_500L;

    private final Messages messages;
    private final LongSupplier clock;
    private final FlagRules flags;
    private final LandFlags landFlags;

    /** At most one. Two plugins answering for the same block is two sets of rules and no tie-breaker. */
    private volatile LandProvider provider;

    private final Set<UUID> bypassing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastRefusal = new ConcurrentHashMap<>();

    /** @param clock milliseconds; injected so the refusal throttle can be tested without waiting for it */
    public Land(LandPolicy policy, Messages messages, LongSupplier clock) {
        this.messages = messages;
        this.clock = clock;
        this.flags = new FlagRules(policy);
        this.landFlags = new LandFlags(this, flags);
    }

    // ------------------------------------------------------------------------ who answers

    /**
     * Registers the plugin that knows where the protected ground is.
     *
     * @return false when somebody is already registered, in which case nothing changes — the first one keeps
     *         the job, and the refusal is logged rather than swallowed
     */
    public boolean provider(LandProvider candidate) {
        if (candidate == null) {
            return false;
        }
        LandProvider existing = provider;
        if (existing != null) {
            log.warn("{} offered to answer land questions, but {} already does. Ignoring the second one — "
                            + "two answers for the same block cannot both be enforced.",
                    candidate.name(), existing.name());
            return false;
        }
        provider = candidate;
        log.info("Land questions are answered by {}.", candidate.name());
        return true;
    }

    /** Stands the provider down — called when the plugin that registered it stops. */
    public void withdraw(LandProvider registered) {
        if (provider == registered && registered != null) {
            provider = null;
            log.info("{} has stopped answering land questions; nothing is protected until something does.",
                    registered.name());
        }
    }

    /** Whether anybody is answering at all. */
    public boolean hasProvider() {
        return provider != null;
    }

    /** Who is answering, for the console line and for the diagnostics command. */
    public Optional<LandProvider> provider() {
        return Optional.ofNullable(provider);
    }

    // ------------------------------------------------------------------------ looking ground up

    /** The protected area at this spot, if there is one and if anybody knows. */
    public Optional<ProtectedArea> areaAt(Location location) {
        LandProvider answering = provider;
        if (answering == null || location == null) {
            return Optional.empty();
        }
        return answering.at(location);
    }

    /**
     * The area a player is considered to be in.
     *
     * <p>Not always the one under their feet — see {@link LandProvider#around}. Use this rather than
     * {@link #areaAt} whenever the subject is a player, or somebody standing on their own border flickers
     * between two answers several times a second.
     */
    public Optional<ProtectedArea> areaAround(Player player) {
        LandProvider answering = provider;
        if (answering == null || player == null) {
            return Optional.empty();
        }
        return answering.around(player);
    }

    /**
     * Whether it is safe to do something sweeping to this whole world.
     *
     * <p>{@code ALLOWED} means somebody checked and there is nothing protected in it. {@code REFUSED} means
     * there is. {@code UNKNOWN} means nobody is answering — and read with {@link LandVerdict#orRefuse()} that
     * stops a farm-world regeneration rather than deleting somebody's house on the strength of a missing
     * plugin.
     */
    public LandVerdict safeToReshape(World world) {
        LandProvider answering = provider;
        if (answering == null || world == null) {
            return LandVerdict.UNKNOWN;
        }
        return answering.hasAnyIn(world) ? LandVerdict.REFUSED : LandVerdict.ALLOWED;
    }

    // ------------------------------------------------------------------------ the admin bypass

    public boolean isBypassing(Player player) {
        return bypassing.contains(player.getUniqueId())
                && hasAny(player, BYPASS_PERMISSION, BYPASS_PERMISSION_CORE);
    }

    /**
     * Turns the bypass on or off.
     *
     * @return whether it is now on
     */
    public boolean toggleBypass(Player player) {
        UUID id = player.getUniqueId();
        if (bypassing.remove(id)) {
            return false;
        }
        bypassing.add(id);
        return true;
    }

    /**
     * Forgets a player entirely — called when they leave.
     *
     * <p>Both maps, and both for the same reason: without this they grow by one entry for every player who has
     * ever been on the server, and an admin who logs back in is silently still bypassing everything.
     */
    public void forget(UUID id) {
        bypassing.remove(id);
        lastRefusal.remove(id);
    }

    public boolean isServerAdmin(Player player) {
        return hasAny(player, ADMIN_PERMISSION, ADMIN_PERMISSION_CORE);
    }

    // ------------------------------------------------------------------------ the decision

    /**
     * Whether this player may do that on this piece of ground.
     *
     * <ol>
     *   <li>Unprotected ground — nothing to enforce, so yes.</li>
     *   <li>A bypassing admin — yes, and deliberately first, so broken ground can be fixed.</li>
     *   <li>Otherwise whatever the area says. Trust lists, bans, delegated rights and everything else resolve
     *       behind {@link ProtectedArea#may}, which is why Core models none of them.</li>
     * </ol>
     */
    public boolean has(ProtectedArea area, Player player, LandAction action) {
        if (area == null) {
            return true;
        }
        if (isBypassing(player)) {
            return true;
        }
        return area.may(player.getUniqueId(), action);
    }

    /** The three-way answer, for a caller that needs to tell "no" from "nobody knows". */
    public LandVerdict verdict(Player player, Location location, LandAction action) {
        if (provider == null) {
            return LandVerdict.UNKNOWN;
        }
        Optional<ProtectedArea> area = areaAt(location);
        if (area.isEmpty()) {
            return LandVerdict.ALLOWED;
        }
        return has(area.get(), player, action) ? LandVerdict.ALLOWED : LandVerdict.REFUSED;
    }

    /** The plain yes/no, treating unprotected and unknown ground alike. */
    public boolean can(Player player, Location location, LandAction action) {
        Optional<ProtectedArea> area = areaAt(location);
        return area.isEmpty() || has(area.get(), player, action);
    }

    /**
     * What a listener calls: allowed, or refused with the explanation already sent.
     *
     * @return true when the action may go ahead
     */
    public boolean allow(Player player, Location location, LandAction action) {
        Optional<ProtectedArea> area = areaAt(location);
        if (area.isEmpty()) {
            return true;
        }
        if (has(area.get(), player, action)) {
            return true;
        }
        deny(player, area.get(), action);
        return false;
    }

    /** The same without the message, for the events that arrive many times a tick. */
    public boolean allowSilently(Player player, Location location, LandAction action) {
        Optional<ProtectedArea> area = areaAt(location);
        return area.isEmpty() || has(area.get(), player, action);
    }

    /** Tells a player why not, at most once every {@value #QUIET_AFTER_REFUSAL_MILLIS} milliseconds. */
    public void deny(Player player, ProtectedArea area, LandAction action) {
        long now = clock.getAsLong();
        Long last = lastRefusal.get(player.getUniqueId());
        if (last != null && now - last < QUIET_AFTER_REFUSAL_MILLIS) {
            return;
        }
        lastRefusal.put(player.getUniqueId(), now);
        player.sendActionBar(messages.prefixed("land.denied",
                "claim", area.name(),
                "permission", messages.raw(action.nameKey())));
    }

    // ------------------------------------------------------------------------ flags

    /** The flag resolver, for the screens that show a flag rather than enforce it. */
    public FlagRules flags() {
        return flags;
    }

    /** Flags resolved by location, which is what the enforcement listeners use. */
    public LandFlags landFlags() {
        return landFlags;
    }

    private static boolean hasAny(Player player, String... nodes) {
        for (String node : nodes) {
            if (player.hasPermission(node)) {
                return true;
            }
        }
        return false;
    }
}
