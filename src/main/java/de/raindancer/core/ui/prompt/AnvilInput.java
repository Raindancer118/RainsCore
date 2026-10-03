package de.raindancer.core.ui.prompt;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.prompt.Parsers.Parser;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.AnvilView;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * "Type a value" as a window: an anvil whose name field is the answer. Each keystroke is read and the
 * result slot shows either the value, ready to click, or what is wrong with it — so the player sees the
 * refusal while typing, not after sending.
 *
 * <pre>{@code
 * AnvilInput.open(player, "Name your home", "home", Parsers.name(16),
 *         name -> homes.create(player, name),
 *         () -> menu.open());                     // closed without an answer
 * }</pre>
 *
 * <p>Nothing in the anvil is ever the player's: the paper shown in it is taken back when the window
 * closes, every click in it is refused, and taking the result gives nothing but the answer. Core
 * registers the one listener this needs.
 */
public final class AnvilInput {

    /** One open anvil and what it is asking. */
    static final class Session<T> {
        private final Parser<T> parser;
        private final Consumer<T> onAnswer;
        private final Runnable onCancel;
        private boolean finished;

        Session(Parser<T> parser, Consumer<T> onAnswer, Runnable onCancel) {
            this.parser = parser;
            this.onAnswer = onAnswer == null ? value -> { } : onAnswer;
            this.onCancel = onCancel == null ? () -> { } : onCancel;
        }

        /** What the result slot shows for this text. */
        Shown show(String typed) {
            Parsed<T> parsed = read(typed);
            return parsed.isOk()
                    ? new Shown(true, typed == null ? "" : typed, "Click to use this")
                    : new Shown(false, typed == null ? "" : typed, parsed.problem());
        }

        /** The result was clicked: an answer when the text is one. */
        boolean take(String typed) {
            Parsed<T> parsed = read(typed);
            if (!parsed.isOk() || finished) {
                return false;
            }
            finished = true;
            onAnswer.accept(parsed.value());
            return true;
        }

        /** The window closed: a cancel, unless it was answered. */
        void closed() {
            if (!finished) {
                finished = true;
                onCancel.run();
            }
        }

        private Parsed<T> read(String typed) {
            try {
                return parser.parse(typed == null ? "" : typed);
            } catch (RuntimeException broken) {
                return Parsed.no("That could not be read.");
            }
        }
    }

    /** The result slot's look: the text, and whether it is usable. */
    record Shown(boolean usable, String text, String line) {
    }

    private static final Map<UUID, Session<?>> open = new ConcurrentHashMap<>();

    private static final int INPUT = 0;
    private static final int RESULT = 2;

    private AnvilInput() {
    }

    /**
     * Opens the anvil, on the player's own thread.
     *
     * @param title    what is being asked — text, not markup
     * @param start    what the name field starts with; the value it holds is the default answer
     * @param onCancel the window was closed without an answer; may be null
     */
    public static <T> void open(Player player, String title, String start, Parser<T> parser,
                                Consumer<T> onAnswer, Runnable onCancel) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(parser, "parser");
        AnvilView view = MenuType.ANVIL.builder()
                .title(Component.text(title == null ? "" : title))
                .checkReachable(false)
                .build(player);
        Session<T> session = new Session<>(parser, onAnswer, onCancel);
        Session<?> previous = open.put(player.getUniqueId(), session);
        if (previous != null) {
            previous.closed();
        }
        view.getTopInventory().setItem(INPUT, Icons.of(Material.PAPER,
                "<white>" + Text.literal(start == null || start.isBlank() ? " " : start)));
        player.openInventory(view);
    }

    /** Whether this player has an anvil question open. */
    public static boolean isAsking(UUID player) {
        return player != null && open.containsKey(player);
    }

    static int asking() {
        return open.size();
    }

    static <T> void start(UUID player, Session<T> session) {
        open.put(player, session);
    }

    private static ItemStack resultFor(Shown shown) {
        return shown.usable()
                ? Icons.of(Material.LIME_DYE, "<green>" + Text.literal(shown.text()),
                        List.of("<gray>" + Text.literal(shown.line())))
                : Icons.of(Material.BARRIER, "<red>" + Text.literal(shown.text().isEmpty() ? "…" : shown.text()),
                        List.of("<red>" + Text.literal(shown.line())));
    }

    /** The listener behind every anvil question. Registered once, by Core. */
    public static final class Listener implements org.bukkit.event.Listener {

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPrepare(PrepareAnvilEvent event) {
            Session<?> session = open.get(event.getView().getPlayer().getUniqueId());
            if (session == null || !(event.getView() instanceof AnvilView view)) {
                return;
            }
            event.setResult(resultFor(session.show(view.getRenameText())));
            view.setRepairCost(0);
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onClick(InventoryClickEvent event) {
            UUID who = event.getWhoClicked().getUniqueId();
            Session<?> session = open.get(who);
            if (session == null || !(event.getView() instanceof AnvilView view)) {
                return;
            }
            // Nothing moves in or out of this window, ever.
            event.setCancelled(true);
            if (event.getRawSlot() == RESULT && session.take(view.getRenameText())) {
                open.remove(who, session);
                clear(view);
                event.getWhoClicked().closeInventory();
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onClose(InventoryCloseEvent event) {
            UUID who = event.getPlayer().getUniqueId();
            Session<?> session = open.get(who);
            if (session == null || !(event.getView() instanceof AnvilView)) {
                return;
            }
            open.remove(who, session);
            // Before the anvil hands its contents back: the paper was never theirs.
            clear(event.getView());
            session.closed();
        }

        private static void clear(InventoryView view) {
            view.getTopInventory().setItem(INPUT, null);
            view.getTopInventory().setItem(1, null);
            view.getTopInventory().setItem(RESULT, null);
        }
    }

    /** A player leaving or the server stopping: their question ends as a cancel. */
    public static void forget(UUID player) {
        Session<?> session = player == null ? null : open.remove(player);
        if (session != null) {
            session.closed();
        }
    }
}
