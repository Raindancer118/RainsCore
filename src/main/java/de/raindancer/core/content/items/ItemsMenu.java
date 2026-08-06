package de.raindancer.core.content.items;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every custom item on the server, and the way in to what each one is crafted from.
 *
 * <h2>Why Core owns this page</h2>
 * The same argument as {@code CuesMenu}. Core owns the item registry, so the page that edits an item belongs
 * here — once, for every plugin's items. The plugin this was ported from carried a recipe editor for exactly
 * one of its sixty items; the item moved into this registry and the page did not come with it, so a server
 * owner could see a recipe and never change it.
 *
 * <h2>What a row says</h2>
 * Whether it can be crafted, and from what. That is the question somebody opening this page has: an item with
 * no recipe is one only a command or a loot table can hand out, which is a completely legitimate thing to be
 * and a surprising thing to discover by accident.
 */
public final class ItemsMenu extends PaginatedMenu<CustomItem> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final CustomItems items;
    private final Runnable save;

    /**
     * @param save called after a recipe is written, so whatever persists the registry can flush it. May be
     *             {@code null} where the registry writes itself
     */
    public ItemsMenu(Player viewer, Brand brand, Menu parent, CustomItems items, Runnable save) {
        super(viewer, brand, parent);
        this.items = items;
        this.save = save;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Custom items");
    }

    @Override
    public String breadcrumb() {
        return "Items";
    }

    /** Grouped by the plugin that defined them, then by id — the way somebody actually looks for one. */
    @Override
    protected List<CustomItem> entries() {
        List<CustomItem> all = new ArrayList<>(items.all());
        all.sort(Comparator.comparing(CustomItem::plugin).thenComparing(CustomItem::id));
        return all;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>No custom items",
                "<gray>A plugin defines these as it starts.",
                "<dark_gray>An empty list means none has.");
    }

    @Override
    protected ItemStack icon(CustomItem item) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>" + item.key());
        if (item.isCraftable()) {
            lore.add("<yellow>Craftable:");
            ItemRecipes.crop(item.recipe()).forEach(row -> lore.add("<dark_gray> " + row));
        } else {
            lore.add("<gray>Not craftable.");
            lore.add("<dark_gray>Handed out by a command or found as loot.");
        }
        if (item.abilityKey() != null && !item.abilityKey().isBlank()) {
            lore.add("<aqua>Ability: <dark_gray>" + item.abilityKey());
        }
        lore.add("");
        lore.add("<yellow>Click: what it is crafted from.");

        return Icons.of(item.material(), "<white>" + item.displayName(), lore);
    }

    @Override
    protected void onClick(CustomItem item, InventoryClickEvent event) {
        new RecipeMenu(viewer, brand(), this, items, item, save).open();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Every custom item any plugin on this server has defined.",
                "",
                "<gray>Click one to set what it is crafted from. An item with no",
                "<gray>recipe is handed out by a command or found as loot, which",
                "<gray>is a legitimate thing to be.");
    }
}
