package de.raindancer.core.choose;

import de.raindancer.core.chat.Brand;
import de.raindancer.core.chat.Style;
import de.raindancer.core.effect.Cues;
import de.raindancer.core.gui.Icons;
import de.raindancer.core.gui.Menu;
import de.raindancer.core.gui.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * Picking a block or item, out of everything the server has.
 *
 * <h2>Why Core ships the screen and not just the list</h2>
 * Because the screen is where it goes wrong. Every plugin that needs one writes the same paged grid,
 * and every one of them writes it slightly differently: this one has no search, that one has no back
 * button, the third shows a thousand materials in enum order. A player who has learned one has
 * learned none of the others.
 *
 * <p>So a plugin says what it wants a block <em>for</em>, and gets a chooser:
 *
 * <pre>{@code
 * new ItemChooser(player, brand, parentMenu, "Pick an icon", chosen -> {
 *     settings.set("icon", chosen.name());
 * }).open();
 * }</pre>
 *
 * <p>Sorted into the creative inventory's own drawers, because that is the sorting every player
 * already has in their head.
 */
public final class ItemChooser extends PaginatedMenu<Category> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String heading;
    private final Consumer<Material> chosen;
    private final Catalogue catalogue;

    /**
     * @param heading what the player is picking a block for — shown as the window's title
     * @param chosen  called with what they picked; the menu closes itself first
     */
    public ItemChooser(Player viewer, Brand brand, Menu parent, String heading,
                       Consumer<Material> chosen) {
        this(viewer, brand, parent, heading, chosen, everythingOnThisServer());
    }

    /** The same, over a list somebody else decided — for a plugin offering a shortlist. */
    public ItemChooser(Player viewer, Brand brand, Menu parent, String heading,
                       Consumer<Material> chosen, Catalogue catalogue) {
        super(viewer, brand, parent);
        this.heading = heading == null || heading.isBlank() ? "Choose a block" : heading;
        this.chosen = chosen;
        this.catalogue = catalogue;
    }

    /**
     * A cue for whoever is looking at this window.
     *
     * <p>Through Core rather than {@code playSound}, so a server owner who has rebound the click has
     * rebound this one too — which is the whole point of there being named cues at all.
     */
    private void play(String cue) {
        if (de.raindancer.core.RainsCore.isAvailable()) {
            de.raindancer.core.RainsCore.get().effects().play(viewer().getUniqueId(), cue);
        }
    }

    /**
     * Every material this server would let somebody hold.
     *
     * <p>{@code isItem} needs the registry, which is why this is here and not in {@link Catalogue}:
     * the sorting is testable without a server and this is not.
     */
    public static Catalogue everythingOnThisServer() {
        return new Catalogue(() -> java.util.Arrays.stream(Material.values())
                .filter(material -> !material.isLegacy())
                .filter(Material::isItem)
                .map(Enum::name)
                .toList());
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + heading);
    }

    @Override
    protected List<Category> entries() {
        return catalogue.categories();
    }

    @Override
    protected ItemStack icon(Category category) {
        Material material = Material.matchMaterial(category.icon());
        return Icons.of(material == null ? Material.CHEST : material,
                "<" + Style.itemName() + ">" + category.title(),
                "<" + Style.itemLore() + ">" + catalogue.itemsIn(category).size() + " to choose from",
                "",
                "<" + Style.itemLore() + ">Click to open");
    }

    @Override
    protected void onClick(Category category, InventoryClickEvent event) {
        play(Cues.PAGE);
        new Families(viewer(), brand(), this, category).open();
    }

    /**
     * One drawer's families — the second level.
     *
     * <p>Without this, "Building Blocks" on a modern server is several hundred materials and eleven
     * of every twelve are wood, so anybody looking for deepslate scrolls past six kinds of tree
     * first. A family holding only one thing is folded into "Other" rather than given a page of its
     * own, because clicking through to a page with a single item on it is worse than a longer list.
     */
    private final class Families extends PaginatedMenu<String> {

        private final Category category;

        private Families(Player viewer, Brand brand, Menu parent, Category category) {
            super(viewer, brand, parent);
            this.category = category;
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<" + Style.titleLabel() + ">" + category.title());
        }

        @Override
        protected List<String> entries() {
            return catalogue.groupsIn(category);
        }

        @Override
        protected ItemStack icon(String group) {
            List<String> items = catalogue.itemsIn(category, group);
            // Drawn with the first thing inside it, so the button looks like what it holds rather
            // than like a folder. A family of oak stairs shows oak stairs.
            Material found = items.isEmpty() ? null : Material.matchMaterial(items.getFirst());
            return Icons.of(found == null ? Material.CHEST : found,
                    "<" + Style.itemName() + ">" + group,
                    "<" + Style.itemLore() + ">" + items.size() + " to choose from",
                    "",
                    "<" + Style.itemLore() + ">Click to open");
        }

        @Override
        protected void onClick(String group, InventoryClickEvent event) {
            play(Cues.PAGE);
            new WithinGroup(viewer(), brand(), this, category, group).open();
        }
    }

    /** One family's worth, which is the page anybody actually picks from. */
    private final class WithinGroup extends PaginatedMenu<String> {

        private final Category category;
        private final String group;

        private WithinGroup(Player viewer, Brand brand, Menu parent, Category category,
                            String group) {
            super(viewer, brand, parent);
            this.category = category;
            this.group = group;
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<" + Style.titleLabel() + ">" + group
                    + " <" + Style.itemLore() + ">· " + category.title());
        }

        @Override
        protected List<String> entries() {
            return catalogue.itemsIn(category, group);
        }

        @Override
        protected ItemStack icon(String material) {
            Material found = Material.matchMaterial(material);
            // A name the catalogue knows but this server cannot make a stack of is drawn as a
            // barrier rather than dropped: a hole in the grid is worse than an item saying why.
            return found == null
                    ? Icons.of(Material.BARRIER, "<" + Style.bad() + ">" + Catalogue.readable(material),
                            "<" + Style.itemLore() + ">This server has no such block")
                    : Icons.of(found, "<" + Style.itemName() + ">" + Catalogue.readable(material),
                            "<" + Style.itemLore() + ">Click to choose");
        }

        @Override
        protected void onClick(String material, InventoryClickEvent event) {
            Material found = Material.matchMaterial(material);
            if (found == null) {
                play(Cues.NO);
                return;
            }
            play(Cues.OK);
            // Closed first: a callback that opens another window would otherwise be fighting this
            // one for the same screen, and the player would see whichever won.
            viewer().closeInventory();
            if (chosen != null) {
                chosen.accept(found);
            }
        }
    }
}
