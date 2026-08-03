package de.raindancer.core.data.settings;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The settings, as a window.
 *
 * <h2>What this class is allowed to decide</h2>
 * Nothing. {@link SettingsNavigation} works out which page shows what, what the trail says and
 * whether a click can change a value; this turns that into buttons. Every rule worth a test lives on
 * the other side of that line, which is why there is no {@code SettingsMenuTest} — there would be
 * nothing in it but Bukkit.
 *
 * <h2>The shape of a page</h2>
 * Categories go in the bands, so a page of six subtopics reads as six doors rather than a grid.
 * Settings go in a grid, because a page of them is a list of equal things. A page holding both puts
 * the categories in the top band and the settings below, which is the order somebody reads in.
 */
public final class SettingsMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SettingsNavigation navigation;
    private final Chat chat;

    /**
     * Where the wording comes from.
     *
     * <p>Asked for rather than held, because this menu is built long before the plugin has finished
     * starting and holding a reference taken then would be a reference to nothing.
     */
    private static Messages words() {
        return de.raindancer.core.RainsCore.get().messages();
    }

    private final String path;
    private final SettingsPage page;

    public SettingsMenu(Player viewer, Brand brand, Chat chat, SettingsNavigation navigation,
                        String path, Menu parent) {
        super(viewer, brand, parent);
        this.navigation = navigation;
        this.chat = chat;
        this.path = path;
        this.page = navigation.page(path);
    }

    /** The front page. */
    public static SettingsMenu root(Player viewer, Brand brand, Chat chat,
                                    SettingsNavigation navigation) {
        return new SettingsMenu(viewer, brand, chat, navigation, null, null);
    }

    @Override
    protected Component title() {
        if (page.trail().isEmpty()) {
            return MINI.deserialize("<gray>Settings");
        }
        return MINI.deserialize("<gray>" + String.join(" <dark_gray>▸<gray> ", page.trail()));
    }

    @Override
    public String breadcrumb() {
        return page.isRoot() ? "the settings" : page.title();
    }

    @Override
    protected List<String> helpLines() {
        return page.isRoot()
                ? List.of("Everything every plugin on this server can be told to do.",
                        "Click a category to go in.")
                : List.of("Click a setting to change it.",
                        "Numbers and text are typed in chat.");
    }

    @Override
    protected void render() {
        int column = 1;
        for (SettingsTopic topic : page.subtopics()) {
            if (column > 7) {
                // More than seven categories on one page is a wall, which is the thing this whole
                // tree exists to avoid. Better a subtopic than a second row of doors.
                break;
            }
            band(MenuLayout.WHO, column++, categoryIcon(topic), event -> open(topic.path()));
        }

        int index = 0;
        for (Setting<?> setting : page.settings()) {
            int row = 2 + index / 9;
            if (row > MenuLayout.LAND) {
                break;
            }
            cell(row, index % 9, settingIcon(setting), event -> onClick(setting));
            index++;
        }
    }

    private org.bukkit.inventory.ItemStack categoryIcon(SettingsTopic topic) {
        Material material = topic.icon() == Material.AIR ? Material.BOOK : topic.icon();
        if (material == Material.PLAYER_HEAD) {
            // The viewer's own face, not Steve's. "Your settings" over a default head is a menu that
            // has not noticed who is looking at it.
            return Icons.head(viewer(), "<white>" + topic.title(), navigation.describe(topic));
        }
        return Icons.of(material, "<white>" + topic.title(), navigation.describe(topic));
    }

    private org.bukkit.inventory.ItemStack settingIcon(Setting<?> setting) {
        Material material = setting.icon() == null || setting.icon() == Material.AIR
                ? Material.PAPER : setting.icon();
        // A flag shows what it is at a glance rather than making somebody read the lore for it.
        if (setting.type() == Boolean.class) {
            boolean on = "on".equals(navigation.registry().display(setting.key()));
            material = on ? Material.LIME_DYE : Material.GRAY_DYE;
        }
        return Icons.of(material, "<white>" + setting.title(), navigation.describe(setting));
    }

    private void onClick(Setting<?> setting) {
        SettingsNavigation.Click what = navigation.click(setting.key());
        switch (what) {
            case CYCLED -> {
                navigation.registry().saveAll();
                refresh();
            }
            case NEEDS_TYPING -> {
                // Typed in chat rather than in an anvil: an anvil cannot show what the value is now
                // or what it is allowed to be, and both matter more than not leaving the window.
                viewer.closeInventory();
                chat.tell(viewer, "<gray>Type a new value for <white><name></white>, or "
                                + "<white>cancel</white>.",
                        Chat.arg("name", setting.title()));
                chat.row(viewer, "<dark_gray>  now: <gray>"
                        + navigation.registry().display(setting.key()));
                if (setting.min() != null) {
                    chat.row(viewer, "<dark_gray>  from " + setting.min() + " to " + setting.max());
                }
                if (!SettingsChatInput.expect(viewer, setting.key(), path)) {
                    // Somebody else is already asking them something. Saying so beats quietly
                    // taking over the answer they were about to give to another plugin.
                    chat.raw(viewer, words().prefixed("settings.finish-first"));
                }
            }
            case UNKNOWN -> chat.raw(viewer, words().prefixed("settings.gone"));
        }
    }

    private void open(String childPath) {
        new SettingsMenu(viewer, brand(), chat, navigation, childPath, this).open();
    }

    /** Which page this is, so the chat-input listener can reopen where somebody left off. */
    public String path() {
        return path;
    }
}
