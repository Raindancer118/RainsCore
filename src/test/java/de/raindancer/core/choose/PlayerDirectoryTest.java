package de.raindancer.core.choose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who a plugin can pick, and in what order.
 *
 * <h2>Why picking a player is its own problem</h2>
 * Because "type their name" fails exactly when it matters. Somebody being banned, or unbanned, or
 * having their claim transferred is usually <em>offline</em> — that is the whole reason it is being
 * done through a menu — and their name is the one thing nobody remembers correctly. Capital letters,
 * an underscore, a zero for an O.
 *
 * <p>So the list has to include people who are not here, and it has to be ordered so the person you
 * want is near the top: online first, then whoever was here most recently. Alphabetical is the order
 * that looks tidy and helps nobody.
 */
@DisplayName("the player directory")
class PlayerDirectoryTest {

    private static final long NOW = 1_000_000_000L;

    private static Known player(String name, boolean online, Duration ago) {
        return new Known(UUID.nameUUIDFromBytes(name.getBytes()), name, online,
                NOW - ago.toMillis());
    }

    /** One entry, as the directory sees it. */
    record Known(UUID id, String name, boolean online, long lastSeen) {
    }

    private static final Known ALICE = player("Alice", true, Duration.ZERO);
    private static final Known BOB = player("Bob", true, Duration.ZERO);
    private static final Known CAROL = player("Carol", false, Duration.ofHours(2));
    private static final Known DAVE = player("Dave", false, Duration.ofDays(30));
    private static final Known EVE = player("Eve_2000", false, Duration.ofDays(400));

    private static PlayerDirectory directory() {
        return new PlayerDirectory(() -> List.of(
                new PlayerEntry(DAVE.id(), DAVE.name(), DAVE.online(), DAVE.lastSeen()),
                new PlayerEntry(ALICE.id(), ALICE.name(), ALICE.online(), ALICE.lastSeen()),
                new PlayerEntry(EVE.id(), EVE.name(), EVE.online(), EVE.lastSeen()),
                new PlayerEntry(CAROL.id(), CAROL.name(), CAROL.online(), CAROL.lastSeen()),
                new PlayerEntry(BOB.id(), BOB.name(), BOB.online(), BOB.lastSeen())),
                () -> NOW);
    }

    // ------------------------------------------------------------------ order

    @Nested
    @DisplayName("the order they come in")
    class Ordering {

        @Test
        @DisplayName("people who are here come first")
        void onlineFirst() {
            assertThat(directory().everybody())
                    .extracting(PlayerEntry::name)
                    .startsWith("Alice", "Bob");
        }

        @Test
        @DisplayName("then whoever was here most recently")
        void thenByLastSeen() {
            assertThat(directory().everybody())
                    .extracting(PlayerEntry::name)
                    .as("alphabetical looks tidy and puts the person you want on page four")
                    .containsExactly("Alice", "Bob", "Carol", "Dave", "Eve_2000");
        }

        @Test
        @DisplayName("two people who are both online are in name order, so the list does not jump")
        void onlineAreStable() {
            assertThat(directory().online())
                    .extracting(PlayerEntry::name)
                    .containsExactly("Alice", "Bob");
        }
    }

    // ------------------------------------------------------------------ filtering

    @Nested
    @DisplayName("narrowing it down")
    class Filtering {

        @Test
        @DisplayName("only the people who are here")
        void onlineOnly() {
            assertThat(directory().online()).hasSize(2);
        }

        @Test
        @DisplayName("searching by part of a name, in any case")
        void searching() {
            assertThat(directory().search("car")).extracting(PlayerEntry::name)
                    .containsExactly("Carol");
            assertThat(directory().search("EVE")).extracting(PlayerEntry::name)
                    .as("nobody remembers whether it was Eve_2000 or eve_2000")
                    .containsExactly("Eve_2000");
        }

        @Test
        @DisplayName("an exact name comes first even when it is a substring of another")
        void exactFirst() {
            PlayerDirectory directory = new PlayerDirectory(() -> List.of(
                    new PlayerEntry(UUID.randomUUID(), "Rain", false, NOW - 1),
                    new PlayerEntry(UUID.randomUUID(), "Raindancer118", true, NOW)),
                    () -> NOW);
            assertThat(directory.search("rain").getFirst().name())
                    .as("searching for the name you typed should not put somebody else above them")
                    .isEqualTo("Rain");
        }

        @Test
        @DisplayName("an empty search is everybody, in the usual order")
        void emptySearch() {
            assertThat(directory().search("")).hasSize(5);
            assertThat(directory().search(null)).hasSize(5);
        }

        @Test
        @DisplayName("people nobody has seen for a long time can be left out")
        void seenRecently() {
            assertThat(directory().seenWithin(Duration.ofDays(60)))
                    .extracting(PlayerEntry::name)
                    .as("a server four years old has thousands of names nobody is looking for")
                    .containsExactly("Alice", "Bob", "Carol", "Dave");
        }

        @Test
        @DisplayName("somebody can be left out of the list entirely")
        void excluding() {
            assertThat(directory().excluding(ALICE.id()).everybody())
                    .extracting(PlayerEntry::name)
                    .as("a menu offering to ban yourself is a menu with a bug in it")
                    .doesNotContain("Alice");
        }
    }

    // ------------------------------------------------------------------ one entry

    @Nested
    @DisplayName("one person")
    class Entries {

        @Test
        @DisplayName("how long ago they were here, said the way somebody would")
        void saysWhenTheyWereHere() {
            PlayerEntry carol = directory().everybody().stream()
                    .filter(entry -> entry.name().equals("Carol")).findFirst().orElseThrow();
            assertThat(carol.lastSeenDescribed(NOW)).isEqualTo("2 hours ago");

            PlayerEntry alice = directory().everybody().getFirst();
            assertThat(alice.lastSeenDescribed(NOW))
                    .as("'0 seconds ago' for somebody standing in front of you is nonsense")
                    .isEqualTo("here now");
        }

        @Test
        @DisplayName("somebody with no recorded visit is not pretended to have one")
        void neverSeen() {
            PlayerEntry never = new PlayerEntry(UUID.randomUUID(), "Ghost", false, 0);
            assertThat(never.lastSeenDescribed(NOW)).isEqualTo("never seen");
        }

        @Test
        @DisplayName("an entry with no name is refused rather than shown as blank")
        void needsAName() {
            assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new PlayerEntry(UUID.randomUUID(), " ", false, NOW))).isNotNull();
        }
    }

    // ------------------------------------------------------------------ near and far

    /**
     * Everybody who has ever been here, without the long-gone drowning the list.
     *
     * <p>Leaving them out is wrong — the person being unbanned is usually the one who has not been
     * seen for a year — and mixing them in is wrong too, because a four-year-old server has thousands
     * of them and they bury the six names anybody is actually looking for. So they are all present
     * and clearly ranked, and a menu can show the ranks as sections.
     */
    @Nested
    @DisplayName("near and long gone")
    class Presence {

        @Test
        @DisplayName("everybody the server has ever seen is in the list")
        void nobodyIsDropped() {
            assertThat(directory().everybody())
                    .as("the person being unbanned is usually the one nobody has seen for a year")
                    .hasSize(5);
        }

        @Test
        @DisplayName("each person is ranked by how long ago they were here")
        void ranks() {
            PlayerDirectory directory = directory();
            assertThat(directory.presenceOf(directory.byName("Alice").orElseThrow()))
                    .isEqualTo(de.raindancer.core.choose.Presence.HERE);
            assertThat(directory.presenceOf(directory.byName("Carol").orElseThrow()))
                    .isEqualTo(de.raindancer.core.choose.Presence.RECENTLY);
            assertThat(directory.presenceOf(directory.byName("Eve_2000").orElseThrow()))
                    .as("four hundred days is not 'recently' on anybody's reading")
                    .isEqualTo(de.raindancer.core.choose.Presence.LONG_AGO);
        }

        @Test
        @DisplayName("they come back in sections, in the order a menu should show them")
        void inSections() {
            var sections = directory().bySection();

            assertThat(sections.keySet())
                    .containsExactly(de.raindancer.core.choose.Presence.HERE,
                            de.raindancer.core.choose.Presence.RECENTLY,
                            de.raindancer.core.choose.Presence.LONG_AGO);
            assertThat(sections.get(de.raindancer.core.choose.Presence.HERE))
                    .extracting(PlayerEntry::name).containsExactly("Alice", "Bob");
            assertThat(sections.values().stream().mapToInt(List::size).sum())
                    .as("a section that quietly loses somebody is worse than no sections")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("where the line falls can be moved")
        void theLineIsAdjustable() {
            PlayerDirectory strict = directory().countingRecentAs(Duration.ofHours(1));
            assertThat(strict.presenceOf(strict.byName("Carol").orElseThrow()))
                    .as("what counts as recent on a busy server is not what counts on a quiet one")
                    .isEqualTo(de.raindancer.core.choose.Presence.LONG_AGO);
        }

        @Test
        @DisplayName("each rank says what to call it and what to draw it with")
        void sectionsHaveChrome() {
            for (var presence : de.raindancer.core.choose.Presence.values()) {
                assertThat(presence.title()).isNotBlank();
                assertThat(presence.icon()).isNotBlank();
            }
        }

        @Test
        @DisplayName("somebody the server has a file for but no visit is long gone, not here")
        void neverSeenIsLongAgo() {
            PlayerDirectory directory = new PlayerDirectory(
                    () -> List.of(new PlayerEntry(UUID.randomUUID(), "Ghost", false, 0)),
                    () -> NOW);
            assertThat(directory.presenceOf(directory.byName("Ghost").orElseThrow()))
                    .isEqualTo(de.raindancer.core.choose.Presence.LONG_AGO);
        }
    }
}
