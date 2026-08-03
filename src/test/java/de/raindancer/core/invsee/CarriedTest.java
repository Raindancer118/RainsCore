package de.raindancer.core.invsee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What somebody is carrying, as a value.
 *
 * <h2>Why a snapshot rather than a live inventory</h2>
 * Because the alternative is holding a reference to a {@code Player} while a window is open, and a
 * player who logs out mid-view leaves that reference pointing at somebody the server has forgotten.
 * A snapshot also makes the offline case the same shape as the online one: one is read from an
 * inventory, the other from a file, and everything above them cannot tell.
 *
 * <p>Written back explicitly, so nothing is changed by accident: a view that mutates as you look at
 * it cannot be read-only, however carefully the click handler is written.
 */
@DisplayName("what somebody is carrying")
class CarriedTest {

    /**
     * Strings stand in for items. The shape is the whole of what this class knows, and none of it
     * needs a running server to be wrong.
     */
    private static Carried<String> empty() {
        return Carried.empty();
    }

    @Nested
    @DisplayName("its shape")
    class Shape {

        @Test
        @DisplayName("every part has the size the game gives it")
        void sizes() {
            Carried<String> carried = empty();
            assertThat(carried.sizeOf(Section.HOTBAR)).isEqualTo(9);
            assertThat(carried.sizeOf(Section.STORAGE)).isEqualTo(27);
            assertThat(carried.sizeOf(Section.ARMOUR)).isEqualTo(4);
            assertThat(carried.sizeOf(Section.OFF_HAND)).isEqualTo(1);
            assertThat(carried.sizeOf(Section.ENDER_CHEST)).isEqualTo(27);
        }

        @Test
        @DisplayName("everything starts empty rather than null")
        void startsEmpty() {
            Carried<String> carried = empty();
            for (Section section : Section.values()) {
                for (int at = 0; at < carried.sizeOf(section); at++) {
                    assertThat(carried.at(section, at))
                            .as(section + " " + at + " is null, which every caller would have to "
                                    + "check for separately")
                            .isNull();
                }
            }
            assertThat(carried.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a slot outside a part is nothing rather than an exception")
        void outOfRange() {
            Carried<String> carried = empty();
            assertThat(carried.at(Section.HOTBAR, 99)).isNull();
            assertThat(carried.at(Section.HOTBAR, -1)).isNull();
            assertThat(carried.at(null, 0)).isNull();
        }
    }

    /**
     * The awkward half of being a value: one of the two things a snapshot carries is raw bytes read
     * out of a player file, and arrays compare by identity. A snapshot that compared them the
     * obvious way would call every round trip a difference, so every test written on top of it would
     * pass or fail for the wrong reason.
     */
    @Nested
    @DisplayName("comparing two of them")
    class Comparing {

        @Test
        @DisplayName("the same things in the same places are the same snapshot")
        void sameContentsAreEqual() {
            assertThat(empty().with(Section.STORAGE, 1, "a rock"))
                    .isEqualTo(empty().with(Section.STORAGE, 1, "a rock"))
                    .hasSameHashCodeAs(empty().with(Section.STORAGE, 1, "a rock"));
        }

        @Test
        @DisplayName("the same thing in a different place is a different snapshot")
        void placeMatters() {
            assertThat(empty().with(Section.STORAGE, 1, "a rock"))
                    .isNotEqualTo(empty().with(Section.STORAGE, 2, "a rock"))
                    .isNotEqualTo(empty().with(Section.HOTBAR, 1, "a rock"))
                    .isNotEqualTo(empty());
        }

        @Test
        @DisplayName("raw bytes compare by what is in them, not by which array they are")
        void bytesCompareDeeply() {
            Carried<byte[]> one = Carried.<byte[]>empty().with(Section.HOTBAR, 0, new byte[] {1, 2});
            Carried<byte[]> other = Carried.<byte[]>empty()
                    .with(Section.HOTBAR, 0, new byte[] {1, 2});

            assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
            assertThat(one).isNotEqualTo(
                    Carried.<byte[]>empty().with(Section.HOTBAR, 0, new byte[] {1, 3}));
        }
    }

    @Nested
    @DisplayName("changing it")
    class Changing {

        @Test
        @DisplayName("putting something in gives a new snapshot rather than changing this one")
        void isImmutable() {
            Carried<String> before = empty();
            Carried<String> after = before.with(Section.HOTBAR, 0, "a sword");

            assertThat(before.at(Section.HOTBAR, 0))
                    .as("a snapshot that changes underneath a window is not a snapshot")
                    .isNull();
            assertThat(after.at(Section.HOTBAR, 0)).isEqualTo("a sword");
        }

        @Test
        @DisplayName("a change to a slot that is not there is refused rather than ignored")
        void refusesBadSlots() {
            Carried<String> carried = empty();
            assertThat(carried.with(Section.ARMOUR, 9, "a hat"))
                    .as("silently dropping a write is how an item disappears with nobody at fault")
                    .isSameAs(carried);
        }

        @Test
        @DisplayName("it knows whether anything is in it at all")
        void knowsIfEmpty() {
            assertThat(empty().with(Section.STORAGE, 3, "a rock").isEmpty()).isFalse();
        }

        @Test
        @DisplayName("every item can be put through one conversion at once")
        void converts() {
            Carried<Integer> lengths = empty()
                    .with(Section.HOTBAR, 0, "sword")
                    .with(Section.ENDER_CHEST, 26, "pick")
                    .map(String::length);

            assertThat(lengths.at(Section.HOTBAR, 0)).isEqualTo(5);
            assertThat(lengths.at(Section.ENDER_CHEST, 26)).isEqualTo(4);
            assertThat(lengths.count())
                    .as("an empty slot must not be handed to the conversion, or both sides of it "
                            + "have to answer for null")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("one part can be counted on its own")
        void countsOnePart() {
            Carried<String> carried = empty()
                    .with(Section.STORAGE, 0, "one")
                    .with(Section.STORAGE, 5, "two")
                    .with(Section.HOTBAR, 0, "three");

            assertThat(carried.countIn(Section.STORAGE)).isEqualTo(2);
            assertThat(carried.countIn(Section.HOTBAR)).isEqualTo(1);
            assertThat(carried.countIn(Section.ENDER_CHEST)).isZero();
        }
    }
}
