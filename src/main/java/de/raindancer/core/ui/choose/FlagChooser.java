package de.raindancer.core.ui.choose;

import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.world.protection.FlagRules;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.protection.LandFlag;
import de.raindancer.core.world.protection.LandFlagGroup;
import de.raindancer.core.world.protection.ProtectedArea;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Setting the rules on a piece of protected ground.
 *
 * <h2>Why this is here and not in whichever plugin owns the ground</h2>
 * Because the flags are Core's, and a screen for them written per plugin is the same screen three times with
 * three different arrangements — which is precisely how a server ends up looking like a pile of plugins. A
 * claims module, an arena and a plot world all want this page, and none of them should have to draw it.
 *
 * <p>Like the other choosers in this package: hand it what it is choosing for and a callback, and it draws
 * itself. Unlike them it edits rather than picks, so the callback is per change.
 *
 * <h2>What it will not show</h2>
 * A flag this server does not enforce is <b>absent</b>, not greyed. Greying is for something that is somebody
 * else's to change; a disabled flag is not a choice anybody here has, and a toggle that produces no effect is
 * worse than a missing one because the owner believes they have set something. A group whose flags are all
 * disabled loses its button for the same reason.
 *
 * <p>A <em>forced</em> flag does appear, greyed: the rule is in force, the owner simply does not decide it, and
 * hiding it would leave them wondering why mobs still will not spawn.
 */
public final class FlagChooser extends Menu {

    /** Told when a flag is set, so the caller can save and re-render. */
    @FunctionalInterface
    public interface OnChanged {
        /**
         * @param flag     what was set
         * @param audience which tier, or null for all three at once
         * @param value    the new value, or null to forget it and follow the server again
         */
        void changed(LandFlag flag, LandAudience audience, Boolean value);
    }

    private final ProtectedArea area;
    private final FlagRules rules;
    private final OnChanged onChanged;
    private final boolean mayEdit;
    private final String refusal;

    /**
     * @param area      the ground being set up
     * @param mayEdit   whether this viewer may change anything; false draws the whole page greyed
     * @param refusal   what to say on a greyed button — "the owner's to change", "the server decides this"
     * @param onChanged called after a change, so the caller can save
     */
    public FlagChooser(Player viewer, Brand brand, Menu parent, ProtectedArea area, FlagRules rules,
                       boolean mayEdit, String refusal, OnChanged onChanged) {
        super(viewer, brand, parent);
        this.area = area;
        this.rules = rules;
        this.mayEdit = mayEdit;
        this.refusal = refusal == null ? "Not yours to change" : refusal;
        this.onChanged = onChanged;
    }

    @Override
    protected Component title() {
        return Component.text("Rules");
    }

    @Override
    protected List<String> helpLines() {
        return List.of(
                "<gray>The rules here, by subject.",
                "",
                "<gray>Each group says how many you have set yourself.",
                "<dark_gray>Anything you have not touched follows the server.");
    }

    @Override
    protected void render() {
        List<LandFlagGroup> groups = shownGroups();
        int column = 1;
        int band = MenuLayout.WHO;

        for (LandFlagGroup group : groups) {
            band(band, column, iconFor(group), click -> new FlagPage(viewer(), brand(), this, group).open());
            column++;
            if (column > 4) {
                // Four to a row. More starts to read as a grid, and a grid is what this exists to stop being.
                column = 1;
                band = Math.min(MenuLayout.LAND, band + 1);
            }
        }

        if (groups.isEmpty()) {
            band(MenuLayout.RULES, 4, Icons.of(Material.BARRIER, "<gray>Nothing to set",
                    "<gray>This server leaves none of the rules to you."));
        }
    }

    /**
     * The group's button.
     *
     * <p>The player group wears the viewer's own head rather than a generic one. It costs nothing and it is
     * immediately obvious which page is about <em>you</em> — the same reason the member lists use heads instead
     * of named paper.
     */
    private org.bukkit.inventory.ItemStack iconFor(LandFlagGroup group) {
        String name = "<gold>" + words(group.nameKey());
        List<String> lore = lore(group);
        return group == LandFlagGroup.PLAYER
                ? Icons.head(viewer(), name, lore)
                : Icons.of(group.icon(), name, lore);
    }

    // ── which groups and flags are on the screen at all ────────────────────────────────────────────

    private List<LandFlagGroup> shownGroups() {
        List<LandFlagGroup> shown = new ArrayList<>();
        for (LandFlagGroup group : LandFlagGroup.occupied()) {
            if (!enforcedIn(group).isEmpty()) {
                shown.add(group);
            }
        }
        return shown;
    }

    private List<LandFlag> enforcedIn(LandFlagGroup group) {
        List<LandFlag> enforced = new ArrayList<>();
        for (LandFlag flag : group.flags()) {
            if (rules.isEnforced(flag)) {
                enforced.add(flag);
            }
        }
        return enforced;
    }

    private List<String> lore(LandFlagGroup group) {
        List<LandFlag> enforced = enforcedIn(group);
        int decided = 0;
        int mixed = 0;
        int fixed = 0;
        for (LandFlag flag : enforced) {
            if (area.flagOverride(flag, LandAudience.OWNER).isPresent()) {
                decided++;
            }
            if (rules.summarise(area, flag) == FlagRules.Summary.MIXED) {
                mixed++;
            }
            if (!rules.isEditableByOwner(flag)) {
                fixed++;
            }
        }

        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + words(group.descriptionKey()));
        lore.add("");
        lore.add("<dark_gray>" + enforced.size() + " rule(s)");
        lore.add(decided == 0 ? "<dark_gray>all following the server"
                : "<white>" + decided + "</white><gray> set by you");
        if (mixed > 0) {
            lore.add("<yellow>" + mixed + " differ(s) per group");
        }
        if (fixed > 0) {
            lore.add("<dark_gray>" + fixed + " fixed by the server");
        }
        return lore;
    }

    private static String words(String key) {
        return RainsCore.get().messages().raw(key);
    }

    // ── one group's flags ──────────────────────────────────────────────────────────────────────────

    /**
     * The flags of one subject.
     *
     * <p>Left click sets the whole area, right click opens the three tiers. Most owners want "fire off" and
     * nothing more, so the common case is one click and the uncommon one is possible — rather than every flag
     * costing two clicks because any of them might have been the uncommon one.
     */
    private final class FlagPage extends Menu {

        private final LandFlagGroup group;

        private FlagPage(Player viewer, Brand brand, Menu parent, LandFlagGroup group) {
            super(viewer, brand, parent);
            this.group = group;
        }

        @Override
        protected Component title() {
            return Component.text(words(group.nameKey()));
        }

        @Override
        protected void render() {
            List<LandFlag> flags = enforcedIn(group);
            for (int at = 0; at < flags.size(); at++) {
                LandFlag flag = flags.get(at);
                boolean theirs = mayEdit && rules.isEditableByOwner(flag);
                if (theirs) {
                    cell(at / 9, at % 9, button(flag), click -> {
                        if (click.isRightClick() && flag.audienceAware()) {
                            new TierPage(viewer(), brand(), this, flag).open();
                            return;
                        }
                        boolean now = rules.isAllowed(area, flag, LandAudience.OWNER);
                        onChanged.changed(flag, null, !now);
                        refresh();
                    });
                } else {
                    cell(at / 9, at % 9, Icons.locked(button(flag),
                            rules.isEditableByOwner(flag) ? refusal : "The server decides this"), click -> {
                    });
                }
            }
        }

        private org.bukkit.inventory.ItemStack button(LandFlag flag) {
            FlagRules.Summary summary = rules.summarise(area, flag);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + words(flag.descriptionKey()));
            lore.add("");
            lore.add(switch (summary) {
                case ALLOWED -> "<green>✔ allowed";
                case DENIED -> "<red>✘ not allowed";
                case MIXED -> "<yellow>◐ different per group";
            });
            if (area.flagOverride(flag, LandAudience.OWNER).isEmpty()) {
                lore.add("<dark_gray>following the server");
            }
            if (mayEdit && rules.isEditableByOwner(flag)) {
                lore.add("");
                lore.add("<dark_gray>click to change");
                if (flag.audienceAware()) {
                    lore.add("<dark_gray>right click for owners / trusted / visitors");
                }
            }
            return Icons.of(flag.icon(), summary.colour() + words(flag.nameKey()), lore);
        }
    }

    // ── one flag, per tier ─────────────────────────────────────────────────────────────────────────

    /** One rule, set separately for the three kinds of person, plus a way back to the server's default. */
    private final class TierPage extends Menu {

        private final LandFlag flag;

        private TierPage(Player viewer, Brand brand, Menu parent, LandFlag flag) {
            super(viewer, brand, parent, 3);
            this.flag = flag;
        }

        @Override
        protected Component title() {
            return Component.text(words(flag.nameKey()));
        }

        @Override
        protected void render() {
            int column = 2;
            for (LandAudience audience : LandAudience.values()) {
                boolean on = rules.isAllowed(area, flag, audience);
                band(MenuLayout.WHO, column, Icons.of(audience.icon(),
                                (on ? "<green>" : "<red>") + words(audience.nameKey()),
                                "<gray>" + words(audience.descriptionKey()),
                                "",
                                on ? "<green>✔ allowed" : "<red>✘ not allowed",
                                "<dark_gray>click to change"),
                        click -> {
                            onChanged.changed(flag, audience, !on);
                            refresh();
                        });
                column += 2;
            }

            // The button the old screen lacked: an owner who once touched a flag could never get back to
            // "whatever the server says" and stayed pinned to the value it had that day.
            toolbar(4, Icons.of(Material.STRUCTURE_VOID, "<gray>Follow the server again",
                            "<gray>Forget what you set here.",
                            "<dark_gray>this rule goes back to the server's default"),
                    click -> {
                        onChanged.changed(flag, null, null);
                        refresh();
                    });
        }
    }
}
