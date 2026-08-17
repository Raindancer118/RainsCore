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
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;
import java.util.function.Consumer;

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

    /**
     * The clickable half of the typed-value prompt — its own constant so
     * {@code SettingsMenuPromptTest} can parse it without a server, per this class's own note above
     * on why nothing else here has a test.
     *
     * <p>{@code run_command} with no leading slash sends exactly the word "cancel" as an ordinary
     * chat line when clicked — the same line a player could have typed by hand — so it reaches
     * {@link de.raindancer.core.ui.prompt.ChatPrompts#offer} the same way and needs no special
     * casing there. Typing "cancel" still works; this is one more door to the same one.
     */
    static final String CANCEL_BUTTON = "<click:run_command:'cancel'><hover:show_text:'<gray>Click "
            + "to leave it as it is'><white><underlined>cancel</underlined></white></hover></click>";

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

    /** How many category buttons fit in the band — the seven columns between the frame panes. */
    private static final int TOPICS_PER_PAGE = 7;

    private final String path;
    private final SettingsPage page;
    private int topicPage;

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
        List<SettingsTopic> subtopics = page.subtopics();
        int pages = MenuLayout.pageCount(subtopics.size(), TOPICS_PER_PAGE);
        topicPage = MenuLayout.clampPage(topicPage, pages);
        int from = MenuLayout.pageStart(topicPage, TOPICS_PER_PAGE);
        int to = Math.min(subtopics.size(), from + TOPICS_PER_PAGE);

        int column = 1;
        for (int i = from; i < to; i++) {
            SettingsTopic topic = subtopics.get(i);
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

    /**
     * More than seven categories used to be a wall this class quietly built: everything past the
     * seventh was dropped with no error and no warning, which is how random teleport went missing
     * from the front page the day it became the eighth module registered. Paged instead, the same way
     * {@link PaginatedMenu} pages a list too long for one screen.
     */
    @Override
    protected void paintPagingChrome(int chromeRow) {
        int pages = MenuLayout.pageCount(page.subtopics().size(), TOPICS_PER_PAGE);
        if (pages <= 1) {
            return;
        }
        if (topicPage > 0) {
            set(chromeRow + MenuLayout.CHROME_PREVIOUS, Icons.previousPage(topicPage, pages),
                    turnCategoriesTo(topicPage - 1));
        }
        if (topicPage < pages - 1) {
            set(chromeRow + MenuLayout.CHROME_NEXT, Icons.nextPage(topicPage + 2, pages),
                    turnCategoriesTo(topicPage + 1));
        }
        set(chromeRow + MenuLayout.CHROME_PAGE, Icons.pageCounter(topicPage + 1, pages));
    }

    private Consumer<InventoryClickEvent> turnCategoriesTo(int newPage) {
        return event -> {
            topicPage = newPage;
            refresh();
        };
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
            case NEEDS_MATERIAL_CHOICE -> new de.raindancer.core.ui.choose.ItemChooser(viewer, brand(), this,
                    "Choose " + setting.title(),
                    chosen -> {
                        navigation.registry().set(setting.key(), chosen.name());
                        navigation.registry().saveAll();
                        refresh();
                    }).open();
            case NEEDS_TYPING -> {
                // Typed in chat rather than in an anvil: an anvil cannot show what the value is now
                // or what it is allowed to be, and both matter more than not leaving the window.
                viewer.closeInventory();
                chat.tell(viewer, "<gray>Type a new value for <white><name></white>, or "
                                + CANCEL_BUTTON + ".",
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
