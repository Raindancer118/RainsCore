package de.raindancer.core.ui.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where things go on a menu page.
 *
 * <h2>Why the layout is a class of its own</h2>
 * The framework this replaces did its arithmetic inside the class that also owned a Bukkit
 * {@code Inventory}, so none of it could be tested without a server — and the mistakes it made were
 * all arithmetic: a page hanging off the left edge, a button written over the Back button, a paged
 * list eating its own page arrows. Pulling the sums out into something that knows nothing about
 * Minecraft means every one of those is a test instead of an evening of clicking.
 *
 * <h2>The grid</h2>
 * Six rows, and every page reads the same way, so what a player learns on their claim carries over
 * to their town and to the admin panel:
 * <pre>
 * Row 0   ·  ·  A  ·  H  ·  B  ·  ·     header: H the subject, A and B two instant looks
 * Row 1   |     band A — who may be here          |
 * Row 2   |     band B — what holds here          |   each band centred in columns 1–7
 * Row 3   |     band C — the land itself          |
 * Row 4         toolbar, around the danger slot
 * Row 5   ◀  ⌂  ·  ◁  #  ▷  ?  ·  ✕     chrome, the framework's alone
 * </pre>
 */
class MenuLayoutTest {

    // ------------------------------------------------------------------ centring

    @Nested
    @DisplayName("a band is centred, not filled from the left")
    class Centring {

        /**
         * Almost every button on these pages is conditional — a feature the server switched off, a
         * perk this claim cannot afford, a door this viewer may not open — so a band with room for
         * seven routinely holds three. Filling from the left left every page hanging off the left
         * edge with a ragged field of panes beside it, and made the same page look different on two
         * servers for no reason a player could see.
         */
        @Test
        @DisplayName("three buttons in a seven-wide band sit in the middle")
        void centresAShortBand() {
            assertThat(MenuLayout.centredShift(1, 3, 1, 7)).isEqualTo(2);
        }

        @Test
        @DisplayName("a band that exactly fills its space does not move")
        void leavesAFullBandAlone() {
            assertThat(MenuLayout.centredShift(1, 7, 1, 7)).isZero();
        }

        @Test
        @DisplayName("one button lands dead centre")
        void centresASingleButton() {
            assertThat(MenuLayout.centredShift(1, 1, 1, 7)).isEqualTo(3);
        }

        @Test
        @DisplayName("a deliberate gap inside a band survives being centred")
        void keepsGaps() {
            // Columns 1 and 4: two subjects with a space between them, which is meant.
            int shift = MenuLayout.centredShift(1, 4, 1, 7);
            assertThat(1 + shift).isEqualTo(3);
            assertThat(4 + shift).isEqualTo(6);
        }

        @Test
        @DisplayName("a block too wide to fit starts at the first column rather than overflowing")
        void clampsAnOversizedBand() {
            assertThat(MenuLayout.centredShift(1, 12, 1, 7)).isEqualTo(0);
        }

        /**
         * Rounding up puts a block that cannot sit dead centre one to the right rather than one to
         * the left, which reads better beside a chrome row anchored on its left-hand Back button.
         */
        @Test
        @DisplayName("an even block in an odd space leans right, consistently")
        void roundsTheSameWayEveryTime() {
            int shift = MenuLayout.centredShift(1, 2, 1, 7);
            assertThat(1 + shift).isEqualTo(4);
            assertThat(2 + shift).isEqualTo(5);
        }
    }

    // ------------------------------------------------------------------ slots

    @Nested
    @DisplayName("the grid")
    class Grid {

        @Test
        @DisplayName("a band and column resolve to the slot the grid says")
        void resolvesBandSlots() {
            assertThat(MenuLayout.bandSlot(MenuLayout.WHO, 1)).isEqualTo(10);
            assertThat(MenuLayout.bandSlot(MenuLayout.RULES, 4)).isEqualTo(22);
            assertThat(MenuLayout.bandSlot(MenuLayout.LAND, 7)).isEqualTo(34);
        }

        @Test
        @DisplayName("a band or column outside the grid is pulled back into it")
        void clampsOutOfRange() {
            assertThat(MenuLayout.bandSlot(99, 99)).isEqualTo(MenuLayout.bandSlot(MenuLayout.LAND, 7));
            assertThat(MenuLayout.bandSlot(-5, -5)).isEqualTo(MenuLayout.bandSlot(MenuLayout.WHO, 1));
        }

        @Test
        @DisplayName("the header slots are where the grid says")
        void resolvesHeaderSlots() {
            assertThat(MenuLayout.HEADER_LEFT).isEqualTo(2);
            assertThat(MenuLayout.HEADER_SUBJECT).isEqualTo(4);
            assertThat(MenuLayout.HEADER_RIGHT).isEqualTo(6);
        }

        @Test
        @DisplayName("the danger slot is dead centre of the bottom row, in line with the subject")
        void putsDangerUnderTheSubject() {
            assertThat(MenuLayout.dangerSlot(6) % 9)
                    .as("the thing and the button that destroys it stand in one line")
                    .isEqualTo(MenuLayout.HEADER_SUBJECT);
            assertThat(MenuLayout.dangerSlot(6)).isEqualTo(49);
        }

        @Test
        @DisplayName("the chrome row is the last one, whatever the page's height")
        void findsTheChromeRow() {
            assertThat(MenuLayout.chromeRowStart(6)).isEqualTo(45);
            assertThat(MenuLayout.chromeRowStart(3)).isEqualTo(18);
        }
    }

    // ------------------------------------------------------------------ placement

    @Nested
    @DisplayName("laying out a page")
    class Placement {

        @Test
        @DisplayName("a band's buttons keep their order once centred")
        void keepsOrder() {
            Map<Integer, Integer> placed = MenuLayout.placeRow(List.of(1, 2, 3),
                    MenuLayout.WHO, 1, 7);
            assertThat(placed.values()).containsExactly(
                    MenuLayout.WHO * 9 + 3, MenuLayout.WHO * 9 + 4, MenuLayout.WHO * 9 + 5);
        }

        @Test
        @DisplayName("a grid row may use the frame columns, unlike a band")
        void gridsUseTheWholeWidth() {
            Map<Integer, Integer> placed = MenuLayout.placeRow(List.of(0, 8), 2, 0, 8);
            assertThat(placed.values()).containsExactly(18, 26);
        }

        @Test
        @DisplayName("an empty row places nothing")
        void placesNothing() {
            assertThat(MenuLayout.placeRow(List.of(), MenuLayout.WHO, 1, 7)).isEmpty();
        }

        @Test
        @DisplayName("the short last row of a grid sits under the middle of the rows above it")
        void centresARaggedLastRow() {
            Map<Integer, Integer> full = MenuLayout.placeRow(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8),
                    1, 0, 8);
            Map<Integer, Integer> ragged = MenuLayout.placeRow(List.of(0, 1, 2), 2, 0, 8);
            int fullMiddle = (full.get(0) % 9 + full.get(8) % 9) / 2;
            int raggedMiddle = (ragged.get(0) % 9 + ragged.get(2) % 9) / 2;
            assertThat(raggedMiddle)
                    .as("three of twelve permissions must not trail off to the left")
                    .isEqualTo(fullMiddle);
        }
    }

    // ------------------------------------------------------------------ the chrome row

    /**
     * The bug this design removes: the navigation used to be a method a subclass called at the end
     * of {@code render()}, writing into the bottom row after the content had gone in — so a button
     * on the same slot was overwritten without a word, and a paged list's footer silently ate its
     * own page arrows. Here the framework owns that row outright.
     */
    @Nested
    @DisplayName("the chrome row belongs to the framework")
    class Chrome {

        @Test
        @DisplayName("a page cannot write into it")
        void refusesContentOnTheChromeRow() {
            MenuLayout layout = new MenuLayout(6);
            assertThat(layout.accepts(44)).isTrue();
            assertThat(layout.accepts(45)).isFalse();
            assertThat(layout.accepts(53)).isFalse();
        }

        @Test
        @DisplayName("nor outside the inventory at all")
        void refusesSlotsOutsideThePage() {
            MenuLayout layout = new MenuLayout(6);
            assertThat(layout.accepts(-1)).isFalse();
            assertThat(layout.accepts(54)).isFalse();
        }

        @Test
        @DisplayName("a three-row dialog has its chrome on row two, not row five")
        void movesWithThePageHeight() {
            MenuLayout dialog = new MenuLayout(3);
            assertThat(dialog.accepts(17)).isTrue();
            assertThat(dialog.accepts(18)).isFalse();
        }

        @Test
        @DisplayName("the framework itself may write there, which is the whole point")
        void allowsTheFrameworkIn() {
            MenuLayout layout = new MenuLayout(6);
            assertThat(layout.acceptsChrome(45)).isTrue();
            assertThat(layout.acceptsChrome(53)).isTrue();
            assertThat(layout.acceptsChrome(54)).isFalse();
        }
    }

    // ------------------------------------------------------------------ paging

    @Nested
    @DisplayName("paging a long list")
    class Paging {

        @Test
        @DisplayName("a list that fits is one page")
        void onePageWhenItFits() {
            assertThat(MenuLayout.pageCount(10, 45)).isEqualTo(1);
            assertThat(MenuLayout.pageCount(45, 45)).isEqualTo(1);
        }

        @Test
        @DisplayName("a list that does not fit is split, with the last page holding the remainder")
        void splitsALongList() {
            assertThat(MenuLayout.pageCount(46, 45)).isEqualTo(2);
            assertThat(MenuLayout.pageCount(90, 45)).isEqualTo(2);
            assertThat(MenuLayout.pageCount(91, 45)).isEqualTo(3);
        }

        @Test
        @DisplayName("an empty list is still one page, so it can say it is empty")
        void emptyIsStillAPage() {
            assertThat(MenuLayout.pageCount(0, 45))
                    .as("a page saying 'nothing here' beats a window that will not open")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a page number outside the list is pulled back to one that exists")
        void clampsThePageNumber() {
            assertThat(MenuLayout.clampPage(-3, 4)).isZero();
            assertThat(MenuLayout.clampPage(9, 4)).isEqualTo(3);
        }

        @Test
        @DisplayName("the slice shown on a page is the right window of the list")
        void slicesTheList() {
            assertThat(MenuLayout.pageStart(0, 45)).isZero();
            assertThat(MenuLayout.pageStart(2, 45)).isEqualTo(90);
        }
    }

    // ------------------------------------------------------------------ page size

    @Test
    @DisplayName("a page is a whole number of rows, and never taller than a chest")
    void rowsAreSane() {
        assertThat(new MenuLayout(6).size()).isEqualTo(54);
        assertThat(new MenuLayout(1).size()).isEqualTo(9);
        // Bukkit refuses anything else outright, so it is caught here rather than at open time.
        assertThatThrownBy(() -> new MenuLayout(7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MenuLayout(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
