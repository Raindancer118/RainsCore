package de.raindancer.core.ui.checklist;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A checklist as a screen: a green or red light per check, the reason under each, a click to fix what
 * can be fixed, and — when there is something to start — a button that only lights up once nothing is
 * red.
 *
 * <p>The list is asked for again every time the screen is drawn, so it always shows what is true now:
 * after a fix, after somebody joined, after another admin changed a setting.
 */
public class ChecklistMenu extends PaginatedMenu<Checklist.Check> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Supplier<Checklist> checks;
    private final String proceedLabel;
    private final Consumer<Player> proceed;

    /** A list with nothing to start — a health check, a "why is this not working" page. */
    public ChecklistMenu(Player viewer, Brand brand, Menu parent, Supplier<Checklist> checks) {
        this(viewer, brand, parent, checks, null, null);
    }

    /**
     * @param proceedLabel what the start button says — "Start the countdown"; null for no button
     * @param proceed      what it does; only clickable while the list is {@link Checklist#ready() ready}
     */
    public ChecklistMenu(Player viewer, Brand brand, Menu parent, Supplier<Checklist> checks,
                         String proceedLabel, Consumer<Player> proceed) {
        super(viewer, brand, parent);
        this.checks = Objects.requireNonNull(checks, "checks");
        this.proceedLabel = proceedLabel;
        this.proceed = proceed;
    }

    private Checklist now() {
        Checklist list = checks.get();
        return list == null ? Checklist.titled("Checks") : list;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + Text.literal(now().title()));
    }

    @Override
    public String breadcrumb() {
        return now().title();
    }

    @Override
    protected List<Checklist.Check> entries() {
        // Grouped, so a mode's own checks sit together under its heading.
        List<Checklist.Check> ordered = new ArrayList<>();
        now().byGroup().values().forEach(ordered::addAll);
        return ordered;
    }

    @Override
    protected void decorate() {
        Checklist list = now();
        set(MenuLayout.HEADER_SUBJECT, Icons.of(list.ready() ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                list.ready() ? "<" + Style.ok() + ">Ready" : "<" + Style.bad() + ">Not ready yet",
                "<" + Style.itemLore() + ">" + Text.literal(list.summary()),
                list.ready() ? "" : "<" + Style.itemLore() + ">Click a red line to fix it, where it says how."));
        if (proceedLabel != null && proceed != null) {
            toolbar(4, list.ready(), Icons.of(Material.LIME_CONCRETE,
                            "<" + Style.ok() + ">" + Text.literal(proceedLabel),
                            "<" + Style.itemLore() + ">Everything above is in order."),
                    "Fix the red lines first",
                    click -> proceed.accept(viewer));
        }
        super.decorate();
    }

    @Override
    protected ItemStack icon(Checklist.Check check) {
        List<String> lore = new ArrayList<>();
        if (!check.group().isEmpty()) {
            lore.add("<dark_gray>" + Text.literal(check.group()));
        }
        if (!check.detail().isEmpty()) {
            lore.add("<" + Style.itemLore() + ">" + Text.literal(check.detail()));
        }
        if (!check.ok()) {
            lore.add(check.blocks() ? "<" + Style.bad() + ">Stops the start." : "<" + Style.warn() + ">A warning only.");
            check.fixIfAny().ifPresent(fix -> {
                lore.add("");
                lore.add(fix.allowedFor(viewer)
                        ? "<" + Style.ok() + ">Click: " + Text.literal(fix.label())
                        : "<dark_gray>Somebody with the permission can fix this here.");
            });
        }
        Material light = check.ok() ? Material.LIME_DYE : check.blocks() ? Material.RED_DYE : Material.YELLOW_DYE;
        String colour = check.ok() ? Style.ok() : check.blocks() ? Style.bad() : Style.warn();
        String mark = check.ok() ? "✔ " : check.blocks() ? "✘ " : "⚠ ";
        return Icons.of(light, "<" + colour + ">" + mark + Text.literal(check.label()), lore);
    }

    @Override
    protected void onClick(Checklist.Check check, InventoryClickEvent event) {
        check.fixIfAny().filter(fix -> fix.allowedFor(viewer)).ifPresent(fix -> {
            fix.action().accept(viewer);
            // The fix may have opened a screen of its own; only redraw if this one is still showing.
            if (viewer.getOpenInventory().getTopInventory().getHolder() == this) {
                refresh();
            }
        });
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>What has to be right first.",
                "<gray>Green is fine, yellow is a warning, red stops it.",
                "<gray>Click a red line to fix it where it offers to.");
    }
}
