package de.raindancer.core.moderation.punishment;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ban.ProfileBanList;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Keeps our bans and the server's own ban list saying the same thing.
 *
 * <h2>Why bother</h2>
 * Because the server already has one. Vanilla owns {@code /ban}, {@code /pardon} and
 * {@code banned-players.json}, and every other moderation plugin ever written also writes there. A
 * server running this with its own separate list would have two ban lists that disagree: an admin
 * bans somebody with a tool we do not know about, our history shows nothing, and the moderator
 * reading it draws the wrong conclusion.
 *
 * <p>So it goes both ways:
 * <ul>
 *   <li><b>Out:</b> a ban made here is written to Bukkit's list too. Which means it keeps working if
 *       this plugin is ever removed — a ban that quietly lifts itself when somebody uninstalls a
 *       plugin is not a ban.</li>
 *   <li><b>In:</b> a ban made by vanilla or another plugin is taken into our record the first time
 *       we notice, so the history is the whole history rather than only our part of it.</li>
 * </ul>
 *
 * <h2>Why the vanilla list stays authoritative for joining</h2>
 * It is not — {@link PunishmentGuard} decides. But because both are written, either one alone still
 * stops the player, and the two agreeing is what makes that safe rather than confusing.
 */
public final class VanillaBanBridge {

    private static final LogChannel log = Log.of("moderation");
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    /** What the server's ban list records as the source, so an admin can see where it came from. */
    private static final String SOURCE = "RainsCore";

    private final Punishments punishments;

    public VanillaBanBridge(Punishments punishments) {
        this.punishments = punishments;
    }

    /**
     * The server's ban list, typed.
     *
     * <p>{@code Bukkit.getBanList(BanList.Type.PROFILE)} is generic over a wildcard and will not
     * accept a profile without this — the raw form compiles to a list of {@code ?} that nothing can
     * be added to.
     */
    private static ProfileBanList list() {
        return Bukkit.getBanList(BanList.Type.PROFILE);
    }

    /**
     * Writes a ban to the server's own list as well.
     *
     * <p>Called after {@link Punishments#punish}, so a ban survives this plugin being removed.
     */
    public void mirrorBan(UUID player, String reason, Instant endsAt) {
        try {
            list().addBan(Bukkit.createProfile(player), reason,
                    endsAt == null ? null : Date.from(endsAt), SOURCE);
        } catch (RuntimeException failure) {
            // Not fatal: our own record is already written and the guard already refuses them. The
            // mirror is belt and braces, and losing it costs only the uninstall case.
            log.warn(failure, "Could not write {}'s ban to the server's own ban list.", player);
        }
    }

    /** Takes a ban off the server's list when it is lifted here. */
    public void mirrorPardon(UUID player) {
        try {
            list().pardon(Bukkit.createProfile(player));
        } catch (RuntimeException failure) {
            log.warn(failure, "Could not take {}'s ban off the server's own ban list.", player);
        }
    }

    /**
     * Takes a ban made elsewhere into our record.
     *
     * <p>Called when somebody is refused entry and we have nothing on them: it means a ban exists
     * that we did not make. Recording it means the moderator reading the history sees the whole
     * story rather than only the part we happened to be responsible for.
     *
     * @return whether anything was imported
     */
    public boolean importIfBannedElsewhere(UUID player) {
        if (punishments.isActive(player, PunishmentKind.BAN)) {
            return false;
        }
        try {
            BanEntry<? super PlayerProfile> entry =
                    list().getBanEntry(Bukkit.createProfile(player));
            if (entry == null) {
                return false;
            }
            Duration length = entry.getExpiration() == null ? null
                    : Duration.between(Instant.now(), entry.getExpiration().toInstant());
            if (length != null && (length.isNegative() || length.isZero())) {
                return false;
            }
            punishments.punish(player, PunishmentKind.BAN, null,
                    reasonOf(entry) + " (from the server's own ban list)", length);
            log.info("{} was already banned outside this plugin; that ban is now in the history.",
                    player);
            return true;
        } catch (RuntimeException failure) {
            log.warn(failure, "Could not read the server's ban list for {}.", player);
            return false;
        }
    }

    private static String reasonOf(BanEntry<? super PlayerProfile> entry) {
        String reason = entry.getReason();
        return reason == null || reason.isBlank() ? "no reason given" : reason;
    }

    /** Whether the server's own list has this player banned, whatever we think. */
    public boolean isBannedByTheServer(UUID player) {
        try {
            return list().isBanned(Bukkit.createProfile(player));
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** Every player the server's own list has banned, so a first run can take them all in. */
    public int importAll() {
        int imported = 0;
        try {
            // getEntries() is declared over "? super PlayerProfile", so the target comes back as
            // Object as far as the compiler is concerned; the profile is what it actually is.
            for (BanEntry<? super PlayerProfile> entry : list().getEntries()) {
                if (!(entry.getBanTarget() instanceof PlayerProfile profile)) {
                    continue;
                }
                UUID id = profile.getId();
                if (id != null && importIfBannedElsewhere(id)) {
                    imported++;
                }
            }
        } catch (RuntimeException failure) {
            log.warn(failure, "Could not read the server's ban list.");
        }
        return imported;
    }
}
