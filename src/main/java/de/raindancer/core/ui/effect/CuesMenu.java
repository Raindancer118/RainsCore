package de.raindancer.core.ui.effect;

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
import java.util.Locale;
import java.util.Map;

/**
 * Every sound and particle cue on the server, and what each one is bound to.
 *
 * <h2>Why Core owns this page</h2>
 * Core owns the cues, so Core has to own the page that changes them. Until it did, a server owner who wanted a
 * different cannon had nowhere to go: the plugin that used to hold seven menus for browsing, layering and
 * auditioning sounds had been ported onto this registry, and the registry had no screen. The answer to "how do
 * I change what a cannon sounds like" was "edit a file that no longer exists".
 *
 * <p>One page for every plugin, which is the same argument as the registry itself. A server owner who rebinds
 * what a countdown sounds like rebinds it once, here, for everything that asks for a countdown — rather than
 * once per plugin in a file per plugin, and differently each time.
 *
 * <h2>What a row shows, and why all of it</h2>
 * The cue's name, what it plays, and how many layers. The layer count is not decoration: the difference between
 * a cannon and a bang is fifteen sounds, and a page showing only the first one would make every heavily-tuned
 * cue look like a plain one — which is exactly how somebody flattens their own sound design by accident.
 *
 * <p>A cue that has been silenced is shown greyed rather than hidden, because silence is a decision somebody
 * made and has to be able to undo. A cue nothing has defined is not shown at all: it does not exist.
 */
public final class CuesMenu extends PaginatedMenu<String> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** How many layers are listed on a row before it says "and more". Six lines is a readable tooltip. */
    private static final int LAYERS_SHOWN = 6;

    private final Effects effects;
    private final Runnable save;

    /**
     * @param save called after a rebinding, so whatever persists cues can write them. May be {@code null} for
     *             a host that keeps them in memory only — a page that pretended to save would be worse
     */
    public CuesMenu(Player viewer, Brand brand, Menu parent, Effects effects, Runnable save) {
        super(viewer, brand, parent);
        this.effects = effects;
        this.save = save;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Sounds and particles");
    }

    @Override
    public String breadcrumb() {
        return "Cues";
    }

    /**
     * Every defined cue, grouped by the plugin that owns it.
     *
     * <p>By owner and then by name, because that is how somebody looks for one: a server owner wanting to
     * quieten the Hunger Games' cannon is not scanning an alphabetical list of two hundred cues from six
     * plugins. The prefix before the colon is the grouping, and it is already there in every name.
     */
    @Override
    protected List<String> entries() {
        List<String> names = new ArrayList<>(effects.all().keySet());
        names.sort(Comparator.comparing(CuesMenu::ownerOf).thenComparing(name -> name));
        return names;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>No cues are defined",
                "<gray>A plugin defines these as it starts.",
                "<dark_gray>An empty list means nothing has asked for one yet.");
    }

    @Override
    protected ItemStack icon(String cue) {
        Effect effect = effects.boundTo(cue).orElse(Effect.silence());
        List<String> lore = new ArrayList<>();

        if (effect.isSilent()) {
            lore.add("<dark_gray>Silenced.");
            lore.add("<gray>Somebody switched this off on purpose.");
        } else {
            List<SoundSequence.Step> steps = effect.sounds().steps();
            if (!steps.isEmpty()) {
                // The count first, because it is the thing a glance has to catch: fifteen layers is a
                // different object from one, and a row that showed only the first would hide that.
                lore.add("<yellow>" + steps.size() + " sound"
                        + (steps.size() == 1 ? "" : "s")
                        + (effect.sounds().lengthMillis() > 0
                                ? " <dark_gray>over " + effect.sounds().lengthMillis() + "ms" : ""));
                for (int i = 0; i < Math.min(steps.size(), LAYERS_SHOWN); i++) {
                    SoundSequence.Step step = steps.get(i);
                    lore.add("<dark_gray> · " + step.sound().key()
                            + (step.delayMillis() > 0 ? " +" + step.delayMillis() + "ms" : ""));
                }
                if (steps.size() > LAYERS_SHOWN) {
                    lore.add("<dark_gray> · and " + (steps.size() - LAYERS_SHOWN) + " more");
                }
            }
            List<ParticleCue> bursts = effect.bursts().bursts();
            if (!bursts.isEmpty()) {
                lore.add("<aqua>" + bursts.size() + " particle layer"
                        + (bursts.size() == 1 ? "" : "s"));
                for (int i = 0; i < Math.min(bursts.size(), LAYERS_SHOWN); i++) {
                    lore.add("<dark_gray> · " + bursts.get(i).particle()
                            + " ×" + bursts.get(i).count());
                }
            }
        }
        lore.add("");
        lore.add("<yellow>Left-click: hear it.");
        lore.add("<aqua>Right-click: change it.");

        ItemStack icon = Icons.of(iconFor(effect), "<white>" + cue, lore);
        return effect.isSilent() ? Icons.locked(icon, "Silenced") : icon;
    }

    /**
     * What a cue is drawn as.
     *
     * <p>Told apart by what it does rather than by its name: a cue with particles looks like particles, one
     * with only sound looks like a note block. A name-based guess would be wrong for every cue a plugin named
     * something this method has not heard of, which is all of them.
     */
    private static Material iconFor(Effect effect) {
        if (effect.isSilent()) {
            return Material.STRUCTURE_VOID;
        }
        if (!effect.bursts().isNothing() && effect.sounds().isSilent()) {
            return Material.BLAZE_POWDER;
        }
        if (!effect.bursts().isNothing()) {
            return Material.FIREWORK_ROCKET;
        }
        return Material.NOTE_BLOCK;
    }

    @Override
    protected void onClick(String cue, InventoryClickEvent event) {
        if (event.isRightClick()) {
            new CueMenu(viewer, brand(), this, effects, cue, save).open();
            return;
        }
        // Heard where the viewer is standing, not at a place — this is an audition, and a cue auditioned at
        // the middle of the world is one nobody can hear.
        effects.play(viewer.getUniqueId(), cue);
    }

    /** The plugin a cue belongs to: everything before the colon, or "core" for anything without one. */
    static String ownerOf(String cue) {
        int colon = cue.indexOf(':');
        return colon < 0 ? "core" : cue.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Every sound and particle any plugin on this server plays.",
                "<gray>Changing one here changes it everywhere it is used.",
                "",
                "<yellow>Left-click</yellow> <gray>hears a cue where you stand.</gray>",
                "<aqua>Right-click</aqua> <gray>opens it: layers, silence, or back to default.</gray>");
    }

    /** Every cue, for a host that wants to say how many there are. */
    public Map<String, Effect> everything() {
        return effects.all();
    }
}
