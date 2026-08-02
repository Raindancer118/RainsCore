package de.raindancer.core.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where things go on a menu page. Knows nothing about Minecraft.
 *
 * <h2>Why the sums live here and not in {@link Menu}</h2>
 * The framework this replaces did its arithmetic inside the class that also owned the Bukkit
 * inventory, so none of it could be tested without a server — and every mistake it made was
 * arithmetic: a page hanging off the left edge, a button written over the Back button, a paged list
 * eating its own page arrows. With the sums in a class that has never heard of an
 * {@code ItemStack}, each of those is a test rather than an evening of clicking.
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
 * Columns 0 and 8 of the bands stay empty on purpose: the panes there frame the content into a
 * visible 7×3 field, which is what gives the ordering without costing a click of extra depth.
 */
public final class MenuLayout {

    /** Left-hand header slot: an instant look, not a page. */
    public static final int HEADER_LEFT = 2;
    /** Centre of the header: what this page is about. */
    public static final int HEADER_SUBJECT = 4;
    /** Right-hand header slot: the second instant look, or the details page. */
    public static final int HEADER_RIGHT = 6;

    /** Band A — who may be here. */
    public static final int WHO = 1;
    /** Band B — what holds here, and what it offers. */
    public static final int RULES = 2;
    /** Band C — the thing itself, and what it costs or earns. */
    public static final int LAND = 3;
    /** The row holding the tools. */
    public static final int TOOLBAR_ROW = 4;

    /** A full page. Only the small dialogs deviate. */
    public static final int PAGE_ROWS = 6;

    /** Columns of the chrome row, relative to its first slot. */
    public static final int CHROME_BACK = 0;
    public static final int CHROME_HOME = 1;
    public static final int CHROME_PREVIOUS = 3;
    /** Dead centre: the page counter, or the one irreversible action. */
    public static final int CHROME_PAGE = 4;
    public static final int CHROME_DANGER = 4;
    public static final int CHROME_NEXT = 5;
    public static final int CHROME_HELP = 6;
    public static final int CHROME_CLOSE = 8;

    private final int rows;

    /**
     * @param rows 1 to 6; Bukkit refuses anything else, so it is caught here rather than at the
     *             moment somebody opens the window
     */
    public MenuLayout(int rows) {
        if (rows < 1 || rows > PAGE_ROWS) {
            throw new IllegalArgumentException(
                    "A chest menu is between 1 and 6 rows, not " + rows + ".");
        }
        this.rows = rows;
    }

    public int rows() {
        return rows;
    }

    public int size() {
        return rows * 9;
    }

    /** The first slot of the chrome row. */
    public int chromeRowStart() {
        return chromeRowStart(rows);
    }

    public static int chromeRowStart(int rows) {
        return (rows - 1) * 9;
    }

    /**
     * Whether a page's own content may be written here.
     *
     * <p>False for the chrome row. Silently ignoring the write beats overwriting the Back button,
     * and it means the class of bug is gone rather than merely tested for.
     */
    public boolean accepts(int slot) {
        return slot >= 0 && slot < size() && slot < chromeRowStart();
    }

    /** Whether the framework may write here — the same, plus the chrome row. */
    public boolean acceptsChrome(int slot) {
        return slot >= 0 && slot < size();
    }

    /** Whether this page is tall enough for the full chrome: home, paging, help. */
    public boolean hasFullChrome() {
        return rows >= PAGE_ROWS;
    }

    // ------------------------------------------------------------------------ the grid

    /**
     * The slot a band and column resolve to <em>before</em> centring — the nominal grid, used by the
     * few places that want a fixed middle slot and by the tests.
     */
    public static int bandSlot(int band, int column) {
        return clamp(band, WHO, LAND) * 9 + clamp(column, 1, 7);
    }

    /** The slot a toolbar column resolves to before centring. */
    public static int toolbarSlot(int column) {
        return TOOLBAR_ROW * 9 + clamp(column, 1, 7);
    }

    /**
     * The one irreversible action's slot: dead centre of the very bottom row.
     *
     * <p>That is where a page ends, and it is the same column as the subject in the header, so the
     * thing and the button that destroys it stand in one line. It is the only content the framework
     * lets onto the chrome row, and it is written last, so nothing can be laid over it.
     */
    public static int dangerSlot(int rows) {
        return chromeRowStart(rows) + CHROME_DANGER;
    }

    // ------------------------------------------------------------------------ placement

    /**
     * How far a row of buttons has to move right to sit in the middle of the columns it may use.
     *
     * <p>The whole block moves as one, so a gap a page left on purpose — the pantry and the weather
     * knob are two subjects and read as such — survives being centred. A block too wide to fit
     * simply starts at the first column.
     *
     * @return the number of columns to add to every button's own column
     */
    public static int centredShift(int firstUsed, int lastUsed, int firstColumn, int lastColumn) {
        int span = lastUsed - firstUsed + 1;
        int available = lastColumn - firstColumn + 1;
        if (span >= available) {
            return firstColumn - firstUsed;
        }
        // Rounding up puts a block that cannot sit dead centre one to the right rather than one to
        // the left, which reads better beside a chrome row anchored on its left-hand Back button.
        return firstColumn + (available + 1 - span) / 2 - firstUsed;
    }

    /**
     * Where each of a row's buttons lands, once the row is centred.
     *
     * @param columns    the columns the page asked for, in order
     * @param row        which row of the page
     * @param firstColumn the leftmost column this row may use — 1 for a band, 0 for a grid
     * @return each requested column mapped to its slot, in the order given
     */
    public static Map<Integer, Integer> placeRow(List<Integer> columns, int row,
                                                 int firstColumn, int lastColumn) {
        Map<Integer, Integer> placed = new LinkedHashMap<>();
        if (columns == null || columns.isEmpty()) {
            return placed;
        }
        List<Integer> sorted = columns.stream().sorted().toList();
        int shift = centredShift(sorted.getFirst(), sorted.getLast(), firstColumn, lastColumn);
        for (int column : columns) {
            placed.put(column, row * 9 + clamp(column + shift, firstColumn, lastColumn));
        }
        return placed;
    }

    // ------------------------------------------------------------------------ paging

    /**
     * How many pages a list of this length needs.
     *
     * <p>Always at least one: a page saying "nothing here" beats a window that will not open.
     */
    public static int pageCount(int entries, int perPage) {
        if (perPage <= 0) {
            return 1;
        }
        return Math.max(1, (entries + perPage - 1) / perPage);
    }

    /** A page number pulled back to one that exists. */
    public static int clampPage(int page, int pageCount) {
        return clamp(page, 0, Math.max(0, pageCount - 1));
    }

    /** The index into the list where a page begins. */
    public static int pageStart(int page, int perPage) {
        return Math.max(0, page) * perPage;
    }

    private static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(most, value));
    }
}
