package de.raindancer.core.ui.choose;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Everybody a plugin could pick, in an order that puts the right person near the top.
 *
 * <h2>Why picking a player is its own problem</h2>
 * Because "type their name" fails exactly when it matters. Somebody being banned, unbanned, or
 * having a claim transferred is usually <em>offline</em> — that is generally why it is being done
 * through a menu at all — and their name is the one thing nobody remembers correctly: a capital
 * letter, an underscore, a zero for an O. A list that only holds who is online is a list that cannot
 * do the job it exists for.
 *
 * <h2>The order</h2>
 * Online first, then whoever was here most recently. Alphabetical is the order that looks tidy and
 * helps nobody: on a server four years old it puts the person you want on page eleven.
 *
 * <h2>Why the people are injected</h2>
 * {@code Bukkit.getOfflinePlayers()} reads the whole player data directory and needs a server. The
 * ordering, the searching and the filtering are what go wrong, so they are on this side of that line.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread as far as this class goes. Whether the supplier is depends on the supplier —
 * the Bukkit one touches disk, so a caller should not be asking it every tick.
 */
public final class PlayerDirectory {

    /** Online first, then most recently seen, then by name so a tie does not shuffle about. */
    private static final Comparator<PlayerEntry> ORDER =
            Comparator.comparing(PlayerEntry::online).reversed()
                    .thenComparing(Comparator.comparingLong(PlayerEntry::lastSeen).reversed())
                    .thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT));

    /**
     * Where the line between "recently" and "long ago" falls by default.
     *
     * <p>A month: long enough that somebody who plays at weekends is still near the top, short enough
     * that a four-year-old server's thousands of one-visit names are not.
     */
    private static final Duration RECENTLY = Duration.ofDays(30);

    private final Supplier<List<PlayerEntry>> people;
    private final LongSupplier clock;
    private final Set<UUID> hidden;
    private final Duration recently;

    public PlayerDirectory(Supplier<List<PlayerEntry>> people, LongSupplier clock) {
        this(people, clock, Set.of(), RECENTLY);
    }

    private PlayerDirectory(Supplier<List<PlayerEntry>> people, LongSupplier clock,
                            Set<UUID> hidden, Duration recently) {
        this.people = people;
        this.clock = clock;
        this.hidden = hidden;
        this.recently = recently;
    }

    /**
     * The same directory with a different idea of what "recently" means.
     *
     * <p>What counts as recent on a server people play every evening is not what counts on one that
     * runs a season every summer.
     */
    public PlayerDirectory countingRecentAs(Duration recently) {
        return new PlayerDirectory(people, clock, hidden,
                recently == null || recently.isNegative() ? RECENTLY : recently);
    }

    /** How long ago somebody was here, as a rank. */
    public Presence presenceOf(PlayerEntry entry) {
        if (entry == null) {
            return Presence.LONG_AGO;
        }
        if (entry.online()) {
            return Presence.HERE;
        }
        if (entry.lastSeen() <= 0) {
            // A file with no recorded visit. Long ago rather than "here" — the alternative puts a
            // name nobody has ever seen at the top of the list.
            return Presence.LONG_AGO;
        }
        return clock.getAsLong() - entry.lastSeen() <= recently.toMillis()
                ? Presence.RECENTLY : Presence.LONG_AGO;
    }

    /**
     * Everybody, in sections, in the order a menu should show them.
     *
     * <p>Every rank is present even when empty is not — an empty section is a heading with nothing
     * under it — but nobody is ever dropped: the sum of the sections is the whole list.
     */
    public java.util.Map<Presence, List<PlayerEntry>> bySection() {
        // Read once. This asked the supplier once per rank, and the Bukkit supplier reads the whole
        // player data directory off disk — so opening a chooser hit the disk and sorted thousands of
        // offline players three times over, on whatever thread the menu was drawn on.
        List<PlayerEntry> everybody = everybody();
        java.util.Map<Presence, List<PlayerEntry>> sections = new java.util.LinkedHashMap<>();
        for (Presence presence : Presence.values()) {
            List<PlayerEntry> theirs = everybody.stream()
                    .filter(entry -> presenceOf(entry) == presence)
                    .toList();
            if (!theirs.isEmpty()) {
                sections.put(presence, theirs);
            }
        }
        return sections;
    }

    /** Everybody in one rank. */
    public List<PlayerEntry> inSection(Presence presence) {
        return bySection().getOrDefault(presence, List.of());
    }

    /** Everybody the server knows about, in the order above. */
    public List<PlayerEntry> everybody() {
        return people.get().stream()
                .filter(entry -> !hidden.contains(entry.id()))
                .sorted(ORDER)
                .toList();
    }

    /** Just the people who are here. */
    public List<PlayerEntry> online() {
        return everybody().stream().filter(PlayerEntry::online).toList();
    }

    /**
     * Everybody seen within this long, plus everybody online.
     *
     * <p>For a server old enough that the full list is thousands of names nobody is looking for.
     */
    public List<PlayerEntry> seenWithin(Duration recently) {
        if (recently == null) {
            return everybody();
        }
        long since = clock.getAsLong() - recently.toMillis();
        return everybody().stream()
                .filter(entry -> entry.online() || entry.lastSeen() >= since)
                .toList();
    }

    /**
     * Everybody whose name contains this, in any case; an exact match first.
     *
     * <p>The exact-match rule earns its keep with names like {@code Rain} and
     * {@code Raindancer118}: searching for the shorter one must not put the longer one above it.
     */
    public List<PlayerEntry> search(String text) {
        if (text == null || text.isBlank()) {
            return everybody();
        }
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        return everybody().stream()
                .filter(entry -> entry.name().toLowerCase(Locale.ROOT).contains(wanted))
                .sorted(Comparator.comparing((PlayerEntry entry) ->
                        entry.name().equalsIgnoreCase(wanted) ? 0 : 1))
                .toList();
    }

    /** One person by id. */
    public Optional<PlayerEntry> byId(UUID id) {
        return id == null ? Optional.empty()
                : everybody().stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    /** One person by exactly their name, in any case. */
    public Optional<PlayerEntry> byName(String name) {
        return name == null ? Optional.empty()
                : everybody().stream().filter(entry -> entry.name().equalsIgnoreCase(name.trim()))
                        .findFirst();
    }

    /**
     * The same directory without these people in it.
     *
     * <p>For leaving the person doing the picking out of their own list — a menu offering to ban
     * yourself is a menu with a bug in it.
     */
    public PlayerDirectory excluding(UUID... ids) {
        Set<UUID> without = new java.util.HashSet<>(hidden);
        for (UUID id : ids) {
            if (id != null) {
                without.add(id);
            }
        }
        return new PlayerDirectory(people, clock, Set.copyOf(without), recently);
    }

    public int size() {
        return everybody().size();
    }
}
