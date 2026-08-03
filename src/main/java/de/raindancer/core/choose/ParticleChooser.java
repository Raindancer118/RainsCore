package de.raindancer.core.choose;

import de.raindancer.core.chat.Brand;
import de.raindancer.core.chat.Style;
import de.raindancer.core.gui.Icons;
import de.raindancer.core.gui.Menu;
import de.raindancer.core.gui.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * Picking a particle — and seeing it before you commit.
 *
 * <h2>The part that makes it a chooser rather than a list</h2>
 * Left-click <em>spawns it in front of you</em>. Nobody knows what {@code SCULK_CHARGE_POP} looks
 * like, and picking one by reading the name is picking at random; the same problem as sounds and the
 * same answer.
 *
 * <p>Right-click takes it. A particle that will not show up without a colour or a block says so on
 * its own button, because a setting that silently spawns nothing is the worst kind to hand somebody.
 */
public final class ParticleChooser extends PaginatedMenu<ParticleGroup> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String heading;
    private final Consumer<String> chosen;
    private final ParticleCatalogue catalogue;

    public ParticleChooser(Player viewer, Brand brand, Menu parent, String heading,
                           Consumer<String> chosen) {
        this(viewer, brand, parent, heading, chosen, everythingOnThisServer());
    }

    public ParticleChooser(Player viewer, Brand brand, Menu parent, String heading,
                           Consumer<String> chosen, ParticleCatalogue catalogue) {
        super(viewer, brand, parent);
        this.heading = heading == null || heading.isBlank() ? "Choose a particle" : heading;
        this.chosen = chosen;
        this.catalogue = catalogue;
    }

    /** Every particle this server has. */
    public static ParticleCatalogue everythingOnThisServer() {
        return new ParticleCatalogue(() -> java.util.Arrays.stream(Particle.values())
                .map(Enum::name)
                .toList());
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + heading);
    }

    @Override
    protected List<ParticleGroup> entries() {
        return catalogue.groups();
    }

    @Override
    protected ItemStack icon(ParticleGroup group) {
        Material material = Material.matchMaterial(group.icon());
        return Icons.of(material == null ? Material.GLASS : material,
                "<" + Style.itemName() + ">" + group.title(),
                "<" + Style.itemLore() + ">" + catalogue.inGroup(group).size() + " particles",
                "",
                "<" + Style.itemLore() + ">Click to open");
    }

    @Override
    protected void onClick(ParticleGroup group, InventoryClickEvent event) {
        new WithinGroup(viewer(), brand(), this, group).open();
    }

    /** One group's particles — where the looking happens. */
    private final class WithinGroup extends PaginatedMenu<String> {

        private final ParticleGroup group;

        private WithinGroup(Player viewer, Brand brand, Menu parent, ParticleGroup group) {
            super(viewer, brand, parent);
            this.group = group;
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<" + Style.titleLabel() + ">" + group.title());
        }

        @Override
        protected List<String> entries() {
            return catalogue.inGroup(group);
        }

        @Override
        protected ItemStack icon(String particle) {
            Material face = Material.matchMaterial(ParticleCatalogue.iconFor(particle));
            List<String> lore = new java.util.ArrayList<>(List.of(
                    "<" + Style.itemLore() + ">" + particle));
            if (ParticleCatalogue.needsExtraData(particle)) {
                // Said on the button rather than discovered afterwards: this one spawns nothing at
                // all unless whatever uses it supplies a colour or a block.
                lore.add("<" + Style.warn() + ">Needs a colour or a block to show up");
            }
            lore.add("");
            lore.add("<" + Style.itemLore() + ">Left-click to see it");
            lore.add("<" + Style.itemLore() + ">Right-click to choose it");
            return Icons.of(face == null ? Material.GLASS : face,
                    "<" + Style.itemName() + ">" + ParticleCatalogue.readable(particle), lore);
        }

        @Override
        protected void onClick(String particle, InventoryClickEvent event) {
            if (event.isRightClick()) {
                viewer().closeInventory();
                if (chosen != null) {
                    chosen.accept(particle);
                }
                return;
            }
            preview(particle);
        }

        /**
         * Shows the particle in front of the player, through the open window.
         *
         * <p>In front rather than at their feet: a menu covers most of the screen, and a particle
         * spawned underneath it is one the player cannot see, which looks exactly like one that did
         * not spawn.
         */
        private void preview(String particle) {
            Particle found;
            try {
                found = Particle.valueOf(particle);
            } catch (IllegalArgumentException gone) {
                return;
            }
            if (ParticleCatalogue.needsExtraData(particle)) {
                // Skipped rather than attempted: spawning one of these without its data throws on
                // some versions and silently does nothing on others, and neither is a preview.
                return;
            }
            var at = viewer().getEyeLocation().add(viewer().getLocation().getDirection().multiply(2));
            viewer().spawnParticle(found, at, 30, 0.4, 0.4, 0.4, 0.02);
        }
    }
}
