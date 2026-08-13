package de.raindancer.core.world.speedrun;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.messages.Messages;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Every advancement the server knows, to pick the one that ends a speedrun — grouped into vanilla's
 * own five tabs, the same drawers {@code ItemChooser} sorts blocks into and the same reason: a flat
 * list of every advancement on the server is hundreds of entries deep, and "which tab is this on in
 * the actual advancements screen" is the sorting a player already carries in their head.
 *
 * <h2>Why recipe advancements are filtered out</h2>
 * {@code Bukkit.advancementIterator()} includes one per unlockable recipe — hundreds of them, none
 * of which anybody races for. Their keys live under the {@code recipes/} path, the same filter a
 * server owner would apply by hand, so they never reach the list rather than being buried in it.
 *
 * <h2>The icon is the advancement's own</h2>
 * {@link AdvancementDisplay#icon()} is what the vanilla advancements screen already draws for this
 * advancement — reusing its material rather than a blanket {@code PAPER} means a player recognises
 * "Enchant an item" by the enchanting table just as they would in F key, that no longer identifies
 * anything.
 */
public final class SpeedrunAdvancementChooser extends PaginatedMenu<SpeedrunAdvancementChooser.Category> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** One of vanilla's own advancement tabs, or the catch-all for anything a datapack adds elsewhere. */
    record Category(String id, String title, Material icon) {
    }

    private static final List<Category> KNOWN = List.of(
            new Category("story", "Story", Material.CRAFTING_TABLE),
            new Category("nether", "Nether", Material.NETHERRACK),
            new Category("end", "The End", Material.END_STONE),
            new Category("adventure", "Adventure", Material.FILLED_MAP),
            new Category("husbandry", "Husbandry", Material.WHEAT));
    private static final Category OTHER = new Category("other", "Other", Material.CHEST);

    private final SpeedrunLobby lobby;
    private final Messages messages;

    public SpeedrunAdvancementChooser(SpeedrunLobby lobby, Messages messages, Brand brand, Player viewer,
                                      Menu parent) {
        super(viewer, brand, parent);
        this.lobby = lobby;
        this.messages = messages;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Advancement goal");
    }

    @Override
    public String breadcrumb() {
        return "Advancement goal";
    }

    @Override
    protected List<Category> entries() {
        Set<String> present = allKeys().stream().map(SpeedrunAdvancementChooser::categoryIdOf)
                .collect(java.util.stream.Collectors.toSet());
        List<Category> found = new ArrayList<>();
        for (Category known : KNOWN) {
            if (present.contains(known.id())) {
                found.add(known);
            }
        }
        if (present.contains(OTHER.id())) {
            found.add(OTHER);
        }
        return found;
    }

    @Override
    protected ItemStack icon(Category category) {
        long count = allKeys().stream().filter(key -> categoryIdOf(key).equals(category.id())).count();
        return Icons.of(category.icon(), "<white>" + category.title(),
                "<gray>" + count + " to choose from", "", "<gray>Click to open");
    }

    @Override
    protected void onClick(Category category, InventoryClickEvent event) {
        new WithinCategory(viewer(), brand(), this, category).open();
    }

    @Override
    protected void decorate() {
        toolbar(4, Icons.of(Material.BARRIER, "<white>No advancement goal",
                        "<gray>A death is then the only way", "<gray>a run can end."),
                click -> {
                    lobby.settings().set("advancement-key", "");
                    messages.send(viewer(), "speedrun.goal.cleared");
                    backToWhoeverOpenedThis();
                });
        super.decorate();
    }

    /**
     * What a player sees this advancement called — the vanilla advancements screen's own name,
     * falling back to the raw key for one that has no display (totally hidden) or is not valid at all.
     */
    static String friendlyName(String rawKey) {
        NamespacedKey key = rawKey == null || rawKey.isBlank() ? null : NamespacedKey.fromString(rawKey);
        if (key == null) {
            return rawKey;
        }
        Advancement advancement = Bukkit.getAdvancement(key);
        AdvancementDisplay display = advancement == null ? null : advancement.getDisplay();
        if (display == null) {
            return rawKey;
        }
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(display.displayName());
    }

    /** Every advancement key on the server, minus recipes — the base list both levels filter from. */
    private static List<NamespacedKey> allKeys() {
        List<NamespacedKey> keys = new ArrayList<>();
        Iterator<Advancement> all = Bukkit.advancementIterator();
        while (all.hasNext()) {
            NamespacedKey key = all.next().getKey();
            if (!key.getKey().startsWith("recipes/")) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** Vanilla's own tab is the path segment before the first {@code /}; anything else is {@link #OTHER}. */
    private static String categoryIdOf(NamespacedKey key) {
        String path = key.getKey();
        int slash = path.indexOf('/');
        String first = slash < 0 ? "" : path.substring(0, slash);
        for (Category known : KNOWN) {
            if (known.id().equals(first)) {
                return first;
            }
        }
        return OTHER.id();
    }

    /** One tab's worth of advancements — the page anybody actually picks from. */
    private final class WithinCategory extends PaginatedMenu<NamespacedKey> {

        private final Category category;

        private WithinCategory(Player viewer, Brand brand, Menu parent, Category category) {
            super(viewer, brand, parent);
            this.category = category;
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<dark_gray>" + category.title());
        }

        @Override
        public String breadcrumb() {
            return category.title();
        }

        @Override
        protected List<NamespacedKey> entries() {
            List<NamespacedKey> keys = allKeys().stream()
                    .filter(key -> categoryIdOf(key).equals(category.id()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            keys.sort(Comparator.comparing(NamespacedKey::asString));
            return keys;
        }

        @Override
        protected ItemStack icon(NamespacedKey entry) {
            Advancement advancement = Bukkit.getAdvancement(entry);
            AdvancementDisplay display = advancement == null ? null : advancement.getDisplay();
            if (display == null) {
                // Totally hidden advancements have no display at all — see the interface javadoc.
                // Still pickable (the key is real), just without the usual dressing.
                return Icons.of(Material.PAPER, "<white>" + entry.asString());
            }
            Material material = display.icon().getType();
            String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(display.displayName());
            return Icons.of(material, "<white>" + name,
                    "<gray>" + entry.asString(), "", "<gray>" + frameLabel(display.frame()));
        }

        /**
         * Picking closes the whole chooser, not just this page — {@link #backToWhoeverOpenedThis()}
         * would only pop back to the category list, which is still the chooser and would read as
         * nothing having happened. The lobby menu that opened the chooser in the first place is
         * {@code SpeedrunAdvancementChooser.this.parent()}, one level further back than this page's
         * own parent.
         */
        @Override
        protected void onClick(NamespacedKey entry, InventoryClickEvent event) {
            lobby.settings().set("advancement-key", entry.asString());
            messages.send(viewer(), "speedrun.goal.set", "advancement", friendlyName(entry.asString()));
            Menu lobbyMenu = SpeedrunAdvancementChooser.this.parent();
            if (lobbyMenu != null) {
                lobbyMenu.open();
            } else {
                viewer().closeInventory();
            }
        }

        private String frameLabel(AdvancementDisplay.Frame frame) {
            return switch (frame) {
                case CHALLENGE -> "Challenge";
                case GOAL -> "Goal";
                case TASK -> "Task";
            };
        }
    }
}
