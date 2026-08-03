package de.raindancer.core.ui.choose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Picking a player from a list rather than typing their name.
 *
 * <h2>Why this belongs in Core</h2>
 * Five of these already exist — effects, flags, items, particles, sounds — and a player was the one obvious
 * missing kind, so every plugin that needed one asked in chat instead. Asking in chat is the worst version of
 * this question: the player has to know the spelling exactly, capitalisation and all; a typo is indistinguishable
 * from somebody who has never joined; a name that changed since they last logged in cannot be typed at all; and
 * the menu has to be closed to answer, so whatever was half-configured is gone.
 *
 * <p>The ordering is what makes it usable on a real server. Somebody with three hundred names on disk wants the
 * eleven people who are online first, then whoever was here this week, and only then everybody else. That is
 * what {@link PlayerDirectory#bySection()} already works out, so the chooser sorts by presence rather than
 * inventing an order of its own — which also means one server's idea of "recently" applies to every chooser.
 */
class PlayerChoosingTest {

    private static PlayerEntry player(String name, boolean online, long lastSeen) {
        return new PlayerEntry(UUID.nameUUIDFromBytes(name.getBytes()), name, online, lastSeen);
    }

    private static final long NOW = 1_000_000_000L;

    private static PlayerDirectory directoryOf(List<PlayerEntry> people) {
        return new PlayerDirectory(() -> people, () -> NOW);
    }

    @Test
    @DisplayName("everybody online comes before everybody who is not")
    void theOnlineComeFirst() {
        PlayerDirectory directory = directoryOf(List.of(
                player("Offline", false, NOW - Duration.ofDays(30).toMillis()),
                player("Online", true, NOW)));

        List<String> order = new ArrayList<>();
        directory.bySection().forEach((presence, people) ->
                people.forEach(person -> order.add(person.name())));

        assertThat(order)
                .as("the people you are most likely to be picking are the ones standing next to you")
                .startsWith("Online");
    }

    @Test
    @DisplayName("somebody seen this week is offered before somebody seen last year")
    void recentBeatsAncient() {
        PlayerEntry recent = player("Recent", false, NOW - Duration.ofHours(6).toMillis());
        PlayerEntry ancient = player("Ancient", false, NOW - Duration.ofDays(400).toMillis());

        PlayerDirectory directory = directoryOf(List.of(ancient, recent))
                .countingRecentAs(Duration.ofDays(7));

        assertThat(directory.presenceOf(recent)).isNotEqualTo(directory.presenceOf(ancient));
        assertThat(directory.seenWithin(Duration.ofDays(7)))
                .extracting(PlayerEntry::name)
                .containsExactly("Recent");
    }

    @Test
    @DisplayName("a search finds somebody by part of their name")
    void searchIsForgiving() {
        PlayerDirectory directory = directoryOf(List.of(
                player("Raindancer118", false, NOW),
                player("SomebodyElse", false, NOW)));

        assertThat(directory.search("rain"))
                .as("a chooser on a big server is unusable without this, and case is not a thing anybody "
                        + "should have to get right")
                .extracting(PlayerEntry::name)
                .containsExactly("Raindancer118");
    }

    @Test
    @DisplayName("the viewer can be left out, so you cannot pick yourself by accident")
    void excludingWorks() {
        PlayerEntry me = player("Me", true, NOW);
        PlayerEntry them = player("Them", true, NOW);

        assertThat(directoryOf(List.of(me, them)).excluding(me.id()).everybody())
                .as("'transfer this claim to…' offering you yourself is a click that does nothing and "
                        + "looks like a bug")
                .extracting(PlayerEntry::name)
                .containsExactly("Them");
    }

    @Test
    @DisplayName("somebody the server has never seen reads as never, not as 1970")
    void anUnseenPlayerIsDescribedHonestly() {
        assertThat(player("Ghost", false, 0L).lastSeenDescribed(NOW))
                .as("a date in 1970 in a menu is a bug report waiting to happen")
                .doesNotContain("1970");
    }

    @Test
    @DisplayName("an empty server is an empty list, not a crash")
    void nobodyAtAllIsFine() {
        PlayerDirectory directory = directoryOf(List.of());

        assertThat(directory.everybody()).isEmpty();
        assertThat(directory.size()).isZero();
        assertThat(directory.byName("Anybody")).isEmpty();
    }
}
