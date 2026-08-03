package de.raindancer.core.choose;

import de.raindancer.core.RainsCore;
import de.raindancer.core.chat.Brand;
import de.raindancer.core.chat.Style;
import de.raindancer.core.effect.Effect;
import de.raindancer.core.effect.Effects;
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
 * Picking one of the named cues — and hearing it before you commit.
 *
 * <p>The chooser a settings page wants: "which effect should a successful claim play?" is a question
 * about meanings, not about sound keys, and the answer should be one of the names every plugin
 * already shares so that rebinding it later changes this too.
 *
 * <p>Left-click plays it, right-click picks it. Same as the sound chooser, for the same reason:
 * choosing by reading a list of names is choosing at random.
 */
public final class EffectChooser extends PaginatedMenu<String> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String heading;
    private final Consumer<String> chosen;
    private final Effects effects;

    public EffectChooser(Player viewer, Brand brand, Menu parent, String heading,
                         Consumer<String> chosen) {
        super(viewer, brand, parent);
        this.heading = heading == null || heading.isBlank() ? "Choose an effect" : heading;
        this.chosen = chosen;
        this.effects = RainsCore.isAvailable() ? RainsCore.get().effects() : null;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + heading);
    }

    @Override
    protected List<String> entries() {
        return effects == null ? List.of() : List.copyOf(effects.all().keySet());
    }

    @Override
    protected ItemStack icon(String cue) {
        Effect effect = effects == null ? null : effects.all().get(cue);
        String sound = effect == null || effect.sound() == null ? "silent" : effect.sound().key();
        String particles = effect == null || effect.particles() == null
                ? "no particles" : effect.particles().particle().toLowerCase(java.util.Locale.ROOT);
        return Icons.of(iconFor(cue),
                "<" + Style.itemName() + ">" + Catalogue.readable(
                        cue.substring(cue.indexOf(':') + 1).replace('-', '_').toUpperCase(
                                java.util.Locale.ROOT)),
                "<" + Style.itemLore() + ">" + cue,
                "<" + Style.itemLore() + ">" + sound,
                "<" + Style.itemLore() + ">" + particles,
                "",
                "<" + Style.itemLore() + ">Left-click to try it",
                "<" + Style.itemLore() + ">Right-click to choose it");
    }

    /**
     * Something that looks like what the cue means.
     *
     * <p>Guessed from the name rather than configured, because a cue a plugin invented this morning
     * still has to have an icon, and a grid of identical note blocks is not a chooser.
     */
    private static Material iconFor(String cue) {
        String name = cue.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("teleport")) {
            return Material.ENDER_PEARL;
        }
        if (name.contains("no") || name.contains("error") || name.contains("cooldown")) {
            return Material.BARRIER;
        }
        if (name.contains("ok") || name.contains("earn") || name.contains("reward")) {
            return Material.EMERALD;
        }
        if (name.contains("heal")) {
            return Material.GOLDEN_APPLE;
        }
        if (name.contains("hurt")) {
            return Material.IRON_SWORD;
        }
        if (name.contains("magic") || name.contains("ability")) {
            return Material.ENCHANTED_BOOK;
        }
        if (name.contains("open") || name.contains("close") || name.contains("page")) {
            return Material.BOOK;
        }
        if (name.contains("countdown")) {
            return Material.CLOCK;
        }
        if (name.contains("summon")) {
            return Material.EGG;
        }
        if (name.contains("vanish")) {
            return Material.GUNPOWDER;
        }
        return Material.NOTE_BLOCK;
    }

    @Override
    protected void onClick(String cue, InventoryClickEvent event) {
        if (event.isRightClick()) {
            viewer().closeInventory();
            if (chosen != null) {
                chosen.accept(cue);
            }
            return;
        }
        if (effects != null) {
            effects.play(viewer().getUniqueId(), cue);
        }
    }
}
