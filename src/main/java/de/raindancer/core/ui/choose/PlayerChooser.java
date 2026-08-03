package de.raindancer.core.ui.choose;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Picking a player from a list, rather than typing their name into chat.
 *
 * <h2>Why this is Core's and not each plugin's</h2>
 * Five choosers already live here — effects, flags, items, particles, sounds — and a player was the obvious
 * missing kind, so every plugin that needed one fell back to asking in chat. That is the worst version of this
 * question:
 *
 * <ul>
 *   <li>the spelling has to be exact, capitals and all;</li>
 *   <li>a typo is indistinguishable from somebody who has never joined;</li>
 *   <li>somebody who changed their name since they last logged in cannot be typed at all;</li>
 *   <li>and the menu has to be closed to answer it, so whatever was half-configured is lost.</li>
 * </ul>
 *
 * <h2>Ordered by who is actually around</h2>
 * Online first, then whoever was here recently, then everybody else — which is {@link PlayerDirectory#bySection()}
 * rather than an order invented here, so one server's idea of "recently" applies to every chooser at once. On a
 * server with three hundred names on disk that ordering is the difference between a chooser and a phone book.
 *
 * <p>Heads are skinned, because a list of names is a list nobody reads and a wall of faces is one people
 * recognise instantly.
 */
public final class PlayerChooser extends PaginatedMenu<PlayerEntry> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** How recent counts as "somebody would remember them". */
    private static final Duration RECENTLY = Duration.ofDays(7);

    private final String heading;
    private final Consumer<PlayerEntry> chosen;
    private final PlayerDirectory directory;

    /**
     * @param heading  what is being asked — "Transfer to…", "Filter by owner", "Trust somebody"
     * @param exclude  who not to offer; usually the viewer, or people already picked. May be empty
     * @param chosen   handed the pick. Not called if the viewer backs out, so a chooser is always cancellable
     */
    public PlayerChooser(Player viewer, Brand brand, Menu parent, String heading,
                         List<UUID> exclude, Consumer<PlayerEntry> chosen) {
        this(viewer, brand, parent, heading, fromServer(), exclude, chosen);
    }

    /** The same, over a directory the caller supplies — for a narrower list than "everybody the server knows". */
    public PlayerChooser(Player viewer, Brand brand, Menu parent, String heading,
                         PlayerDirectory directory, List<UUID> exclude, Consumer<PlayerEntry> chosen) {
        super(viewer, brand, parent);
        this.heading = heading == null || heading.isBlank() ? "Choose somebody" : heading;
        this.chosen = chosen;
        PlayerDirectory narrowed = directory == null ? fromServer() : directory;
        if (exclude != null && !exclude.isEmpty()) {
            narrowed = narrowed.excluding(exclude.toArray(UUID[]::new));
        }
        this.directory = narrowed.countingRecentAs(RECENTLY);
    }

    /**
     * Everybody the server has a record of, online or not.
     *
     * <p>{@code getOfflinePlayers} reads the whole player directory off disk, which is why this is built once
     * when the screen opens rather than per page: on a long-running server that is thousands of files.
     */
    private static PlayerDirectory fromServer() {
        return new PlayerDirectory(() -> {
            List<PlayerEntry> people = new ArrayList<>();
            for (OfflinePlayer person : Bukkit.getOfflinePlayers()) {
                String name = person.getName();
                if (name == null) {
                    continue;   // a record with no name is one nobody can be shown or asked about
                }
                people.add(new PlayerEntry(person.getUniqueId(), name, person.isOnline(),
                        person.getLastSeen()));
            }
            for (Player online : Bukkit.getOnlinePlayers()) {
                // A player who has joined but not yet been written to disk is missing from the list above.
                if (people.stream().noneMatch(known -> known.id().equals(online.getUniqueId()))) {
                    people.add(new PlayerEntry(online.getUniqueId(), online.getName(), true,
                            System.currentTimeMillis()));
                }
            }
            return people;
        }, System::currentTimeMillis);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<" + Style.titleLabel() + ">" + heading);
    }

    @Override
    protected List<PlayerEntry> entries() {
        // Flattened in section order rather than grouped into rows: a chooser is a list to scan, and the
        // ordering is what carries the grouping. Paging a grouped layout leaves half-empty pages instead.
        List<PlayerEntry> ordered = new ArrayList<>();
        directory.bySection().forEach((presence, people) -> ordered.addAll(people));
        return ordered;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>Nobody to choose from",
                "<dark_gray>this server has no record of anybody yet");
    }

    @Override
    protected ItemStack icon(PlayerEntry person) {
        Presence presence = directory.presenceOf(person);
        List<String> lore = new ArrayList<>();
        lore.add(person.online()
                ? "<green>online now"
                : "<gray>" + person.lastSeenDescribed(System.currentTimeMillis()));
        lore.add("<dark_gray>" + presence.title().toLowerCase(java.util.Locale.ROOT));
        lore.add("");
        lore.add("<dark_gray>click to choose");
        return Icons.head(person.id(), (person.online() ? "<white>" : "<gray>") + person.name(), lore);
    }

    @Override
    protected void onClick(PlayerEntry person, InventoryClickEvent event) {
        if (chosen != null) {
            chosen.accept(person);
        }
    }
}
