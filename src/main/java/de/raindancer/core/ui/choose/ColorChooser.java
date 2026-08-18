package de.raindancer.core.ui.choose;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * Picking one of vanilla's sixteen chat colours from a grid of swatches, rather than clicking a
 * setting sixteen times to cycle round to the one that was wanted.
 *
 * <p>All sixteen fit on one page ({@link PaginatedMenu#PER_PAGE} is 36), so this never actually
 * pages — it rides on {@code PaginatedMenu} anyway rather than reinventing its grid, its title bar
 * and its filler, the same way every other chooser in this package does.
 *
 * <pre>{@code
 * new ColorChooser(player, brand, parentMenu, "Default message colour", current, chosen -> {
 *     settings.set("default-message-colour", chosen.toString());
 * }).open();
 * }</pre>
 */
public final class ColorChooser extends PaginatedMenu<NamedTextColor> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String heading;
    private final NamedTextColor current;
    private final Consumer<NamedTextColor> chosen;

    /**
     * @param heading what the player is picking a colour for — shown as the window's title
     * @param current the colour already chosen, marked in the grid so a player can tell without
     *                reading every swatch's name; null when nothing is chosen yet
     * @param chosen  called with what they picked; the menu closes itself first
     */
    public ColorChooser(Player viewer, Brand brand, Menu parent, String heading,
                        NamedTextColor current, Consumer<NamedTextColor> chosen) {
        super(viewer, brand, parent);
        this.heading = heading == null || heading.isBlank() ? "Choose a colour" : heading;
        this.current = current;
        this.chosen = chosen;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + heading);
    }

    @Override
    protected List<NamedTextColor> entries() {
        return ColorSwatches.ALL;
    }

    @Override
    protected ItemStack icon(NamedTextColor color) {
        boolean chosenAlready = color.equals(current);
        String name = ColorSwatches.readable(color);
        // A block reads as a colour; a sample of actual text in it reads as what somebody typing in
        // this colour would look like, which is the thing being chosen and the block only stands in
        // for. Both together beat either alone — the block scans from across the room, the sample is
        // what the setting is actually for.
        String key = NamedTextColor.NAMES.key(color);
        return Icons.of(ColorSwatches.materialFor(color),
                "<" + Style.itemName() + ">" + name + (chosenAlready ? " <green>✔" : ""),
                "<" + key + ">Sample text Abc123",
                "",
                chosenAlready
                        ? "<" + Style.itemLore() + ">This is it right now"
                        : "<" + Style.itemLore() + ">Click to choose");
    }

    @Override
    protected void onClick(NamedTextColor color, InventoryClickEvent event) {
        if (chosen != null) {
            chosen.accept(color);
        }
        // Back to the page that asked, rather than leaving the viewer looking at nothing.
        backToWhoeverOpenedThis();
    }
}
