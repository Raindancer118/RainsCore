package de.raindancer.core.ui.menu;

import de.raindancer.core.ui.chat.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * "Are you sure?", as a page of its own — the one of these on the server.
 *
 * <h2>Why it is here rather than in each plugin</h2>
 * Because it had been written three times: in the claims module, in the moderation module and in the
 * warps module. Every copy had the same two columns and slightly different words, which is the worst
 * shape for this particular page — three places to fix the next thing in one of, and the copy nobody
 * fixes is always the one guarding the button that deletes something.
 *
 * <p>It is also the page where consistency is the whole feature. <b>No is on the left and Yes is on
 * the right</b>, everywhere, because that is a habit people build: a dialog that swaps them is one
 * they learn to click through and then get wrong exactly once. {@code ConfirmMenuGrammarTest} pins
 * that, since it cannot be asserted by clicking.
 *
 * <h2>Why a page and not a chat prompt</h2>
 * Because the thing being confirmed is on screen. Somebody who opened the wrong warp's page sees the
 * wrong warp's name here, which is the only reason asking helps at all. Three rows rather than six:
 * a question with two answers on a full page reads as an empty page with two buttons lost in it.
 *
 * <h2>What a plugin still decides</h2>
 * The question, the consequences, and one closing line — {@code "This cannot be undone."} for a
 * deletion, {@code "It goes on their record either way."} for a punishment. The wording is the
 * plugin's; the shape is not.
 */
public class ConfirmMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** What most confirmations are about. */
    public static final String CANNOT_BE_UNDONE = "<dark_gray>This cannot be undone.";

    private final String question;
    private final List<String> consequences;
    private final String closingLine;
    private final Runnable onYes;

    /**
     * @param question     the title, as MiniMessage — usually {@code "<red>Delete X?"}
     * @param consequences what saying yes actually does, a line each
     * @param onYes        run on the confirming click, on the viewer's own thread
     */
    public ConfirmMenu(Player viewer, Brand brand, Menu parent, String question,
                       List<String> consequences, Runnable onYes) {
        this(viewer, brand, parent, question, consequences, CANNOT_BE_UNDONE, onYes);
    }

    /**
     * The same, with the closing line said differently.
     *
     * <p>A punishment is not undone by saying no — it goes on the record either way — so
     * "this cannot be undone" would be the wrong sentence there, and a wrong sentence on a
     * confirmation is worse than none.
     */
    public ConfirmMenu(Player viewer, Brand brand, Menu parent, String question,
                       List<String> consequences, String closingLine, Runnable onYes) {
        super(viewer, brand, parent, 3);
        this.question = question;
        this.consequences = consequences == null ? List.of() : List.copyOf(consequences);
        this.closingLine = closingLine;
        this.onYes = onYes;
    }

    @Override
    protected Component title() {
        return MINI.deserialize(question);
    }

    @Override
    public String breadcrumb() {
        return "Are you sure?";
    }

    @Override
    protected void render() {
        List<String> lore = new ArrayList<>(consequences);
        if (closingLine != null && !closingLine.isBlank()) {
            lore.add("");
            lore.add(closingLine);
        }

        // Left. Nothing but going back — never the thing being confirmed, however the caller
        // arranged its lambdas.
        band(MenuLayout.WHO, 6, Icons.of(Material.RED_CONCRETE, "<red>No, leave it alone",
                        "<gray>Nothing happens."),
                click -> leave());

        band(MenuLayout.WHO, 4, Icons.of(Material.BOOK, "<gray>What this does", lore));

        // Right, always.
        band(MenuLayout.WHO, 2, Icons.of(Material.LIME_CONCRETE, "<green>Yes, do it",
                        "<gray>Go ahead."),
                click -> {
                    if (onYes != null) {
                        onYes.run();
                    }
                });
    }
}
