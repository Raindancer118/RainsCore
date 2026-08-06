package de.raindancer.core.ui.choose;

import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.effect.SoundCue;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * Picking a sound, out of the thousand-odd a server knows.
 *
 * <h2>The part that makes it usable</h2>
 * Clicking one <em>plays</em> it. A list of names like {@code block.amethyst_block.chime} is not a
 * chooser — nobody knows what any of them sound like, and picking by reading is picking at random.
 * Left-click hears it, right-click takes it.
 *
 * <p>Grouped by the first word of the key, because Minecraft's own names are already a hierarchy and
 * a different one would only be a second thing to learn.
 */
public final class SoundChooser extends PaginatedMenu<SoundFamily> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String heading;
    private final Consumer<String> chosen;
    private final SoundCatalogue catalogue;

    /**
     * @param heading what they are picking a sound for
     * @param chosen  called with the sound's key; the menu closes itself first
     */
    public SoundChooser(Player viewer, Brand brand, Menu parent, String heading,
                        Consumer<String> chosen) {
        this(viewer, brand, parent, heading, chosen, everythingOnThisServer());
    }

    public SoundChooser(Player viewer, Brand brand, Menu parent, String heading,
                        Consumer<String> chosen, SoundCatalogue catalogue) {
        super(viewer, brand, parent);
        this.heading = heading == null || heading.isBlank() ? "Choose a sound" : heading;
        this.chosen = chosen;
        this.catalogue = catalogue;
    }

    /**
     * Every sound this server has.
     *
     * <p>From the registry rather than the {@code Sound} enum, so a sound added by a resource pack or
     * by a newer version is in the list without this class being changed.
     */
    public static SoundCatalogue everythingOnThisServer() {
        return new SoundCatalogue(() -> {
            List<String> keys = new java.util.ArrayList<>();
            Registry.SOUNDS.forEach(sound -> {
                NamespacedKey key = sound.getKey();
                keys.add(key.getNamespace().equals("minecraft")
                        ? key.getKey() : key.toString());
            });
            return keys;
        });
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + heading);
    }

    @Override
    protected List<SoundFamily> entries() {
        return catalogue.families();
    }

    @Override
    protected ItemStack icon(SoundFamily family) {
        Material material = Material.matchMaterial(family.icon());
        return Icons.of(material == null ? Material.NOTE_BLOCK : material,
                "<" + Style.itemName() + ">" + family.title(),
                "<" + Style.itemLore() + ">" + catalogue.inFamily(family).size() + " sounds",
                "",
                "<" + Style.itemLore() + ">Click to open");
    }

    @Override
    protected void onClick(SoundFamily family, InventoryClickEvent event) {
        new WithinFamily(viewer(), brand(), this, family).open();
    }

    /** One family's sounds — where the listening happens. */
    private final class WithinFamily extends PaginatedMenu<String> {

        private final SoundFamily family;

        private WithinFamily(Player viewer, Brand brand, Menu parent, SoundFamily family) {
            super(viewer, brand, parent);
            this.family = family;
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<" + Style.titleLabel() + ">" + family.title());
        }

        @Override
        protected List<String> entries() {
            return catalogue.inFamily(family);
        }

        @Override
        protected ItemStack icon(String key) {
            // Drawn as the thing that makes the noise — the amethyst block, the bell, the zombie's
            // egg. A grid of identical note blocks is a list of names in a costume, not a chooser.
            Material face = Material.matchMaterial(SoundCatalogue.iconFor(key));
            return Icons.of(face == null ? Material.NOTE_BLOCK : face,
                    "<" + Style.itemName() + ">" + SoundCatalogue.readable(key),
                    "<" + Style.itemLore() + ">" + key,
                    "",
                    "<" + Style.itemLore() + ">Left-click to hear it",
                    "<" + Style.itemLore() + ">Right-click to choose it");
        }

        @Override
        protected void onClick(String key, InventoryClickEvent event) {
            if (event.isRightClick()) {
                if (chosen != null) {
                    chosen.accept(key);
                }
                // Back to the page that asked, rather than leaving the viewer looking at nothing.
                backToWhoeverOpenedThis();
                return;
            }
            // Straight to the player rather than through a named cue: this is the raw sound being
            // auditioned, and running it through the vocabulary would play whatever that name is
            // bound to instead of the one being pointed at.
            if (RainsCore.isAvailable()) {
                viewer().playSound(viewer().getLocation(), key, 1f, 1f);
            }
        }
    }
}
