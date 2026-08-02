package de.raindancer.core.bossbar;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import net.kyori.adventure.bossbar.BossBar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Boss bars, and how many of them a player should have to look at.
 *
 * <h2>Why this is not the action bar's problem again</h2>
 * A player has one action bar and one sidebar, so those are winner-takes-all. Boss bars
 * <em>stack</em>: the client will draw six of them down the top of the screen until there is no
 * screen left to play in. So the question here is not who wins but how many, and which — a cap, and
 * a ranking that fills it. Everything past the cap is remembered rather than refused, and appears
 * the moment something ahead of it goes away.
 *
 * <h2>Shared bars</h2>
 * The thing neither of the others had to deal with. A ghast flight's bar belongs to the flight, not
 * to a player: everyone aboard sees the same bar, and people get on and off while it runs. The bug
 * that taught this lesson is in the ghast lines' history — somebody who got off kept a bar that
 * never moved again, because nothing took it away when they left. So an audience is part of a shared
 * bar's state, and changing it is an ordinary update.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. Per-player state is a {@link ConcurrentHashMap} of slots each mutated under
 * its own monitor, and shared bars are a second map guarded the same way. A shared update touches
 * both, always in that order — shared first, then the players — so two of them cannot deadlock.
 */
public final class BossBars {

    private static final LogChannel log = Log.of("bossbar");

    /**
     * How many bars a player is shown at once.
     *
     * <p>Three. The client will draw more, but a screen with six bars across the top is a screen
     * nobody can play on, and a player who sees six stops reading any of them.
     */
    public static final int MAX_VISIBLE = 3;

    /** One plugin's claim on a place among a player's bars. */
    private record Claim(String owner, BarStyle style, BarPriority priority, long order,
                         BossBar bar, String sharedKey) {
    }

    /** What one player is being shown. Everything here is under the slot's monitor. */
    private static final class Slot {
        private final Map<String, Claim> byOwner = new LinkedHashMap<>();
        /** Which bars are on their screen right now, so only real changes are sent. */
        private final Set<BossBar> visible = new LinkedHashSet<>();
    }

    private final BarViewers viewers;
    private final Map<UUID, Slot> slots = new ConcurrentHashMap<>();
    /** Shared bars by owner and id, each with the audience currently holding it. */
    private final Map<String, Shared> shared = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger updates = new AtomicInteger();

    /** A bar several players are watching, and who they are. */
    private static final class Shared {
        private final BossBar bar;
        private final Set<UUID> audience = new LinkedHashSet<>();
        private BarStyle style;

        private Shared(BossBar bar, BarStyle style) {
            this.bar = bar;
            this.style = style;
        }
    }

    public BossBars(BarViewers viewers) {
        this.viewers = viewers;
    }

    /** How many times a bar has actually been changed. For diagnostics, and for the tests. */
    public int updateCount() {
        return updates.get();
    }

    // ------------------------------------------------------------------------ one player

    /**
     * Shows a bar to one player, or updates the one this owner is already showing them.
     *
     * <p>Meant to be called freely, every tick if that is convenient: an unchanged bar sends
     * nothing, and a bar that is currently crowded out is remembered without disturbing what is on
     * screen.
     */
    public void show(UUID player, String owner, BarStyle style, BarPriority priority) {
        if (player == null || style == null) {
            return;
        }
        String who = clean(owner);
        if (who == null) {
            log.warn("A boss bar for {} was refused: it named no owner.", player);
            return;
        }
        withSlot(player, slot -> {
            Claim existing = slot.byOwner.get(who);
            if (existing != null && existing.sharedKey() == null) {
                if (existing.style().equals(style) && existing.priority() == priority) {
                    return;
                }
                // Mutated in place rather than replaced: the client animates a bar that changes and
                // blinks one that is taken away and given back.
                style.applyTo(existing.bar());
                updates.incrementAndGet();
                slot.byOwner.put(who, new Claim(who, style, orDefault(priority), existing.order(),
                        existing.bar(), null));
            } else {
                slot.byOwner.put(who, new Claim(who, style, orDefault(priority),
                        sequence.incrementAndGet(), style.toBar(), null));
            }
            restack(player, slot);
        });
    }

    /** Takes away the bar this owner was showing this player. */
    public void clear(UUID player, String owner) {
        String who = clean(owner);
        if (player == null || who == null) {
            return;
        }
        withSlot(player, slot -> {
            if (slot.byOwner.remove(who) != null) {
                restack(player, slot);
            }
        });
    }

    // ------------------------------------------------------------------------ many players

    /**
     * Shows one bar to a whole audience — a flight's passengers, a town's citizens.
     *
     * <p>The audience is part of the update: whoever is in it sees the bar, whoever has left it
     * loses it. That is the whole point. Calling this every tick with the current passenger list is
     * the intended use, and is how somebody who gets off stops seeing a bar that would otherwise
     * never move again.
     *
     * @param owner    the plugin
     * @param id       which of that plugin's shared bars this is, e.g. a flight's id
     * @param audience everyone who should see it now; empty ends it
     */
    public void showShared(String owner, String id, Iterable<UUID> audience, BarStyle style,
                           BarPriority priority) {
        String who = clean(owner);
        String key = clean(id);
        if (who == null || key == null || style == null) {
            return;
        }
        String sharedKey = who + "/" + key;
        Set<UUID> wanted = new LinkedHashSet<>();
        if (audience != null) {
            for (UUID member : audience) {
                if (member != null) {
                    wanted.add(member);
                }
            }
        }
        if (wanted.isEmpty()) {
            clearShared(who, key);
            return;
        }

        Shared bar = shared.computeIfAbsent(sharedKey, ignored -> new Shared(style.toBar(), style));
        List<UUID> joined;
        List<UUID> left;
        synchronized (bar) {
            if (!bar.style.equals(style)) {
                style.applyTo(bar.bar);
                bar.style = style;
                updates.incrementAndGet();
            }
            joined = wanted.stream().filter(member -> !bar.audience.contains(member)).toList();
            left = bar.audience.stream().filter(member -> !wanted.contains(member)).toList();
            bar.audience.clear();
            bar.audience.addAll(wanted);
        }

        for (UUID member : joined) {
            withSlot(member, slot -> {
                slot.byOwner.put(sharedKey, new Claim(who, style, orDefault(priority),
                        sequence.incrementAndGet(), bar.bar, sharedKey));
                restack(member, slot);
            });
        }
        for (UUID member : left) {
            withSlot(member, slot -> {
                if (slot.byOwner.remove(sharedKey) != null) {
                    restack(member, slot);
                }
            });
        }
    }

    /** Ends a shared bar, taking it from everybody still holding it. */
    public void clearShared(String owner, String id) {
        String who = clean(owner);
        String key = clean(id);
        if (who == null || key == null) {
            return;
        }
        String sharedKey = who + "/" + key;
        Shared bar = shared.remove(sharedKey);
        if (bar == null) {
            return;
        }
        List<UUID> audience;
        synchronized (bar) {
            audience = List.copyOf(bar.audience);
            bar.audience.clear();
        }
        for (UUID member : audience) {
            withSlot(member, slot -> {
                if (slot.byOwner.remove(sharedKey) != null) {
                    restack(member, slot);
                }
            });
        }
    }

    /** How many shared bars are running. For diagnostics. */
    public int sharedBars() {
        return shared.size();
    }

    // ------------------------------------------------------------------------ housekeeping

    /** Drops everything for a player, including their place in any shared audience. */
    public void forget(UUID player) {
        if (player == null) {
            return;
        }
        for (Shared bar : shared.values()) {
            synchronized (bar) {
                bar.audience.remove(player);
            }
        }
        Slot slot = slots.remove(player);
        if (slot == null) {
            return;
        }
        synchronized (slot) {
            for (BossBar bar : List.copyOf(slot.visible)) {
                hide(player, bar);
            }
            slot.visible.clear();
            slot.byOwner.clear();
        }
    }

    /** Takes every bar away. Called from {@code onDisable}. */
    public void shutdown() {
        for (String key : Set.copyOf(shared.keySet())) {
            int slash = key.indexOf('/');
            clearShared(key.substring(0, slash), key.substring(slash + 1));
        }
        for (UUID player : Set.copyOf(slots.keySet())) {
            forget(player);
        }
    }

    /** Which players we are holding bars for. */
    public Set<UUID> trackedPlayers() {
        return Set.copyOf(slots.keySet());
    }

    /** Every owner currently claiming a bar on this player, in the order they are ranked. */
    public List<String> ownersFor(UUID player) {
        Slot slot = player == null ? null : slots.get(player);
        if (slot == null) {
            return List.of();
        }
        synchronized (slot) {
            return ranked(slot).stream().map(Claim::owner).toList();
        }
    }

    // ------------------------------------------------------------------------ internals

    /**
     * Runs something against a player's slot, with the map-identity re-check.
     *
     * <p>The same race the action bar had: the slot has to come out of the map before its monitor
     * can be taken, and it can be removed in between. Every removal holds the monitor, so a writer
     * that still finds its own slot in the map knows it cannot be removed until it lets go.
     */
    private void withSlot(UUID player, java.util.function.Consumer<Slot> work) {
        while (true) {
            Slot slot = slots.computeIfAbsent(player, key -> new Slot());
            synchronized (slot) {
                if (slots.get(player) != slot) {
                    continue;
                }
                work.accept(slot);
                if (slot.byOwner.isEmpty() && slot.visible.isEmpty()) {
                    slots.remove(player, slot);
                }
                return;
            }
        }
    }

    /** Works out which bars should be on screen and moves the difference. Under the slot monitor. */
    private void restack(UUID player, Slot slot) {
        List<Claim> ranked = ranked(slot);
        Set<BossBar> wanted = new LinkedHashSet<>();
        for (int index = 0; index < Math.min(MAX_VISIBLE, ranked.size()); index++) {
            wanted.add(ranked.get(index).bar());
        }
        for (BossBar going : List.copyOf(slot.visible)) {
            if (!wanted.contains(going)) {
                hide(player, going);
                slot.visible.remove(going);
            }
        }
        for (BossBar coming : wanted) {
            if (slot.visible.add(coming)) {
                show(player, coming);
            }
        }
    }

    /** Highest priority first; on a tie the older claim, so a bar does not jump about. */
    private static List<Claim> ranked(Slot slot) {
        List<Claim> claims = new ArrayList<>(slot.byOwner.values());
        claims.sort(Comparator
                .comparingInt((Claim claim) -> claim.priority().ordinal()).reversed()
                .thenComparingLong(Claim::order));
        return claims;
    }

    private void show(UUID player, BossBar bar) {
        try {
            viewers.show(player, bar);
        } catch (RuntimeException gone) {
            log.debug("Could not show {} a boss bar: {}", player, gone.toString());
        }
    }

    private void hide(UUID player, BossBar bar) {
        try {
            viewers.hide(player, bar);
        } catch (RuntimeException gone) {
            log.debug("Could not take a boss bar from {}: {}", player, gone.toString());
        }
    }

    private static BarPriority orDefault(BarPriority priority) {
        return priority == null ? BarPriority.NORMAL : priority;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
