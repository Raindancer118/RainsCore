package de.raindancer.core.ui.choose;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Picking a creature.
 *
 * <p>The sixth of Core's choosers, and it exists for the reason the other five do: every plugin that
 * lets somebody name a mob otherwise asks them to type {@code CAVE_SPIDER} into chat, which means
 * exact spelling, no way to browse, and a closed menu to answer in.
 *
 * <p>Drawn as spawn eggs, which is the picture of each mob every player already knows — the same
 * reason {@code SoundChooser} plays the sound rather than listing its name.
 *
 * <h2>Two shapes, because two questions</h2>
 * {@link #anything} opens on the drawers: hostile, passive, water, bosses, objects. {@link #toFight}
 * skips them and shows one flat list of what a fight can be made of, because "which mob shall I put
 * in this wave?" has no useful answer under <em>passive</em> and asking somebody to walk past that
 * drawer to find out is asking them to learn where the trap is.
 */
public final class MobChooser extends PaginatedMenu<MobFamily> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String heading;
    private final Consumer<String> chosen;
    private final MobCatalogue catalogue;

    /**
     * Everything, in its drawers.
     *
     * @param heading what they are picking a creature for
     * @param chosen  called with the entity type's name; the menu closes itself first
     */
    public static MobChooser anything(Player viewer, Brand brand, Menu parent, String heading,
                                      Consumer<String> chosen) {
        return new MobChooser(viewer, brand, parent, heading, chosen, everythingOnThisServer());
    }

    /**
     * One flat list of what a fight can be made of.
     *
     * <p>Its own entry point rather than a flag, so a caller cannot get it the wrong way round: the
     * screens that build a wave have no business offering a cow, and the ones that do not care should
     * not have to say so.
     */
    public static Menu toFight(Player viewer, Brand brand, Menu parent, String heading,
                               Consumer<String> chosen) {
        return new WithinFamily(viewer, brand, parent, heading, chosen, everythingOnThisServer(),
                null);
    }

    public MobChooser(Player viewer, Brand brand, Menu parent, String heading,
                      Consumer<String> chosen, MobCatalogue catalogue) {
        super(viewer, brand, parent);
        this.heading = heading == null || heading.isBlank() ? "Choose a creature" : heading;
        this.chosen = chosen;
        this.catalogue = catalogue;
    }

    /**
     * Every creature this server can spawn.
     *
     * <p>From the registry rather than {@code EntityType.values()}, so one added by a newer version is
     * in the list without this class being changed. The unspawnable ones are left out — the player
     * type and the fishing bobber are not things anybody is choosing.
     */
    public static MobCatalogue everythingOnThisServer() {
        return new MobCatalogue(() -> {
            List<String> names = new ArrayList<>();
            Registry.ENTITY_TYPE.forEach(type -> {
                if (type == EntityType.PLAYER || type == EntityType.FISHING_BOBBER
                        || type == EntityType.UNKNOWN) {
                    return;
                }
                NamespacedKey key = type.getKey();
                names.add(key.getKey());
            });
            return names;
        });
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + heading);
    }

    @Override
    protected List<MobFamily> entries() {
        return catalogue.families();
    }

    @Override
    protected ItemStack icon(MobFamily family) {
        return Icons.of(material(family.icon(), Material.SPAWNER),
                "<" + Style.itemName() + ">" + family.title(),
                "<" + Style.itemLore() + ">" + catalogue.inFamily(family).size() + " creatures",
                "",
                "<" + Style.itemLore() + ">Click to open");
    }

    @Override
    protected void onClick(MobFamily family, InventoryClickEvent event) {
        new WithinFamily(viewer(), brand(), this, family.title(), chosen, catalogue, family).open();
    }

    /** A material by name, or the fallback — the registry may not have what a table names. */
    static Material material(String name, Material fallback) {
        Material found = Material.matchMaterial(name);
        return found == null ? fallback : found;
    }

    /**
     * One drawer's creatures, or — with a null family — everything a fight can be made of.
     *
     * <p>Static and self-contained so {@link #toFight} can open it without a parent chooser above it,
     * which is the whole point of that entry point.
     */
    private static final class WithinFamily extends PaginatedMenu<String> {

        private final String heading;
        private final Consumer<String> chosen;
        private final MobCatalogue catalogue;
        private final MobFamily family;

        private WithinFamily(Player viewer, Brand brand, Menu parent, String heading,
                             Consumer<String> chosen, MobCatalogue catalogue, MobFamily family) {
            super(viewer, brand, parent);
            this.heading = heading == null || heading.isBlank() ? "Choose a creature" : heading;
            this.chosen = chosen;
            this.catalogue = catalogue;
            this.family = family;
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<" + Style.titleLabel() + ">" + heading);
        }

        @Override
        public String breadcrumb() {
            return heading;
        }

        @Override
        protected List<String> entries() {
            return family == null ? catalogue.fightable() : catalogue.inFamily(family);
        }

        @Override
        protected ItemStack emptyIcon() {
            return Icons.of(Material.COBWEB,
                    "<" + Style.itemLore() + ">Nothing here",
                    "<" + Style.itemLore() + ">This server knows no creature of that kind.");
        }

        @Override
        protected ItemStack icon(String type) {
            return Icons.of(material(MobCatalogue.iconFor(type), Material.SPAWNER),
                    "<" + Style.itemName() + ">" + MobFamily.readable(type),
                    "<" + Style.itemLore() + ">" + type,
                    "",
                    "<" + Style.itemLore() + ">Click to choose it");
        }

        @Override
        protected void onClick(String type, InventoryClickEvent event) {
            // Closed before the callback, not after: what the caller does next is usually opening
            // another screen, and closing on top of that would shut the one it just opened.
            viewer().closeInventory();
            if (chosen != null) {
                chosen.accept(type);
            }
        }
    }
}
