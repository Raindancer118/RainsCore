package de.raindancer.core.ui.choose;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Picking a number without typing it.
 *
 * <h2>Why Core owns this</h2>
 * Six places needed it — an entry fee, its XP levels, the pantry threshold, an effect level, a claim's depth, a
 * fence height — and each had grown its own answer. Nudge buttons at ±1 and ±10, which is forty clicks to set a
 * fee of four hundred; or a chat prompt, which closes the menu and loses whatever was half-configured.
 *
 * <p>The arithmetic is static and tested on its own, because that is where every bug in the screens this
 * replaces actually was: a stepper that wrapped past its maximum, a range that would not open when a server
 * lowered its limit under an existing value.
 *
 * <h2>Nothing happens until Accept</h2>
 * Back is the way out. A dialog that changes nothing until it is accepted needs no second word for "never
 * mind", and the caller is handed the value once rather than watching it change.
 */
public final class AmountChooser extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** The steps, smallest span outward. ±1 and ±10 alone is why every screen grew a prompt beside it. */
    private static final int[] STEPS = {-100, -10, -1, 1, 10, 100};

    /** Where the steppers sit: three either side of the value, so the number is between the directions. */
    private static final int[] STEP_SLOTS = {0, 1, 2, 6, 7, 8};

    private final String label;
    private final int min;
    private final int max;
    private final IntConsumer onAccept;
    private int value;

    /**
     * @param label what is being set, shown under the number — "Entry fee", "XP levels"
     * @param start where to open; brought inside the range if a server lowered its limit under an existing value
     * @param onAccept handed the value once, when Accept is clicked. Never called if they back out
     */
    public AmountChooser(Player viewer, Brand brand, Menu parent, String label,
                         int start, int min, int max, IntConsumer onAccept) {
        super(viewer, brand, parent, 3);
        this.label = label == null || label.isBlank() ? "Amount" : label;
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        this.value = stepped(start, 0, min, max);
        this.onAccept = onAccept;
    }

    /** The value a step lands on: clamped to the range, never wrapped. */
    public static int stepped(int from, int step, int min, int max) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        long wanted = (long) from + step;
        return (int) Math.max(low, Math.min(high, wanted));
    }

    /** Whether a step would move the value at all. Shown greyed rather than hidden when it would not. */
    public static boolean reachable(int from, int step, int min, int max) {
        return stepped(from, step, min, max) != stepped(from, 0, min, max);
    }

    /** The steps this offers, for the test that pins them. */
    public static List<Integer> steps() {
        List<Integer> all = new java.util.ArrayList<>(STEPS.length);
        for (int step : STEPS) {
            all.add(step);
        }
        return List.copyOf(all);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + label);
    }

    @Override
    protected void render() {
        // The number itself, stacked so the count is legible on the icon as well as in its name.
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.PAPER, "<white><bold>" + value,
                "<gray>" + label,
                "<dark_gray>anything from " + min + " to " + max));

        for (int index = 0; index < STEPS.length; index++) {
            int step = STEPS[index];
            boolean reachable = reachable(value, step, min, max);
            set(STEP_SLOTS[index], Icons.of(
                            step < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                            (step < 0 ? "<red>" : "<green>") + (step > 0 ? "+" : "") + step,
                            reachable ? "<gray>Click to apply" : "<dark_gray>out of range"),
                    click -> {
                        value = stepped(value, step, min, max);
                        refresh();
                    });
        }

        set(11, Icons.of(Material.HOPPER, "<yellow>Least <gray>(" + min + ")"),
                click -> {
                    value = min;
                    refresh();
                });

        set(13, Icons.of(Material.LIME_CONCRETE, "<green><bold>Accept", "<gray>Use " + value),
                click -> {
                    if (onAccept != null) {
                        onAccept.accept(value);
                    }
                    // Back to whoever opened this, so the caller's screen redraws with the new number.
                    // This one always did; the other five did not, which is why it is a shared method now.
                    backToWhoeverOpenedThis();
                });

        set(15, Icons.of(Material.BEACON, "<yellow>Most <gray>(" + max + ")"),
                click -> {
                    value = max;
                    refresh();
                });
    }
}
