package de.raindancer.core.gui;

import de.raindancer.core.chat.Style;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;

import java.util.ArrayList;
import java.util.List;

/**
 * The buttons every menu is made of.
 *
 * <h2>Why the italics are turned off everywhere</h2>
 * Minecraft draws a custom item name in italics unless something says otherwise, and an unspecified
 * decoration inherits from its parent. A button whose name is not explicitly non-italic therefore
 * comes out slanted, which is why the old menus were a patchwork of upright and italic labels
 * depending on which file built them. Every name and every lore line here says so out loud.
 *
 * <p>Colours come from {@link Style}, so a server that changes its palette changes every button in
 * every plugin at once.
 */
public final class Icons {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Icons() {
    }

    /** A button: an icon, a name, and however many lines of explanation. All MiniMessage. */
    public static ItemStack of(Material material, String name, String... lore) {
        return of(material, name, List.of(lore));
    }

    public static ItemStack of(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(text(name == null ? "" : name, Style.itemName()));
            List<Component> lines = new ArrayList<>(lore.size());
            for (String line : lore) {
                lines.add(text(line, Style.itemLore()));
            }
            meta.lore(lines);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * A button showing somebody's actual head.
     *
     * <p>Their skin, not Steve's. A menu that says "Your settings" over a default head is a menu
     * that has not noticed who is looking at it, and one listing players as identical heads is a
     * list you have to read rather than recognise — which is the whole reason heads are used for
     * players in the first place.
     *
     * <p>Falls back to a plain head rather than failing: a player the server has never seen, or one
     * whose skin has not loaded yet, still needs a button.
     */
    public static ItemStack head(OfflinePlayer who, String name, String... lore) {
        return head(who, name, List.of(lore));
    }

    public static ItemStack head(OfflinePlayer who, String name, List<String> lore) {
        ItemStack item = of(Material.PLAYER_HEAD, name, lore);
        if (who == null) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            // setOwningPlayer rather than the deprecated owner-by-name: a name lookup blocks on
            // Mojang's servers, and doing that while drawing a menu freezes the main thread for
            // everybody until it answers.
            skull.setOwningPlayer(who);
            item.setItemMeta(skull);
        }
        return item;
    }

    /** The same, by id — for a player who is not online and may never have been seen. */
    public static ItemStack head(UUID who, String name, String... lore) {
        return head(who == null ? null : Bukkit.getOfflinePlayer(who), name, List.of(lore));
    }

    /**
     * A button that cannot be used, and the reason why.
     *
     * <p>Shown rather than hidden: a player who cannot see a button assumes the feature does not
     * exist, and a player who clicks a live-looking one and gets an error learns to distrust the
     * menu. Greyed with the reason underneath answers the question before it is asked.
     */
    public static ItemStack locked(ItemStack original, String reason) {
        ItemStack item = original == null
                ? new ItemStack(Material.GRAY_DYE)
                : original.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Component name = meta.displayName();
            if (name != null) {
                meta.displayName(name.colorIfAbsent(
                        net.kyori.adventure.text.format.NamedTextColor.GRAY));
            }
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(text("<" + Style.bad() + ">" + (reason == null ? "Not yours to change" : reason),
                    Style.bad()));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** A decorative pane. Nameless, so hovering one says nothing rather than saying "Glass Pane". */
    public static ItemStack filler(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack back(String where) {
        return of(Material.ARROW, "<" + Style.itemName() + ">Back",
                "<" + Style.itemLore() + ">Return to " + (where == null ? "the previous menu" : where));
    }

    public static ItemStack home(String where) {
        return of(Material.COMPASS, "<" + Style.itemName() + ">Home",
                "<" + Style.itemLore() + ">Back to " + (where == null ? "the start" : where));
    }

    public static ItemStack close() {
        return of(Material.BARRIER, "<" + Style.bad() + ">Close",
                "<" + Style.itemLore() + ">Shut this menu");
    }

    public static ItemStack help(List<String> lines) {
        List<String> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add("<" + Style.itemLore() + ">" + line);
        }
        return of(Material.WRITABLE_BOOK, "<" + Style.itemName() + ">What is this?", lore);
    }

    public static ItemStack previousPage(int page, int pages) {
        return of(Material.SPECTRAL_ARROW, "<" + Style.itemName() + ">Previous page",
                "<" + Style.itemLore() + ">Page " + page + " of " + pages);
    }

    public static ItemStack nextPage(int page, int pages) {
        return of(Material.SPECTRAL_ARROW, "<" + Style.itemName() + ">Next page",
                "<" + Style.itemLore() + ">Page " + page + " of " + pages);
    }

    public static ItemStack pageCounter(int page, int pages) {
        return of(Material.PAPER, "<" + Style.itemName() + ">Page " + page + " of " + pages);
    }

    /** MiniMessage with the vanilla italics explicitly switched off. See the class note. */
    private static Component text(String miniMessage, String fallbackColour) {
        String source = miniMessage.startsWith("<") ? miniMessage
                : "<" + fallbackColour + ">" + miniMessage;
        return MINI.deserialize(source).decoration(TextDecoration.ITALIC, false);
    }
}
