package de.raindancer.core.platform.command;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The directory as a written book — one section per plugin, every command clickable.
 *
 * <h2>Why a book and not a chat list</h2>
 * Thirty commands is more than a chat window holds, and a player who scrolls past them has lost
 * them. A book has pages, stays open, and its click handlers survive being read twice.
 *
 * <h2>What a click does</h2>
 * Always <em>suggests</em>, never runs. The claims manual runs the harmless ones because it knows
 * which those are — it was written command by command. This book is built from what plugins report,
 * so it does not know whether {@code /regen} is a page or a demolition, and a directory that can
 * delete something by being skim-read is a trap. Clicking puts the command in the chat bar with the
 * reader's cursor after it, which is what somebody looking a command up wanted anyway.
 *
 * <h2>The page budget</h2>
 * A client silently truncates a page that overruns — no error, the text simply stops — so a section
 * is laid over as many pages as it needs rather than trusting it to fit. The numbers are deliberately
 * conservative: a page that breaks early reads fine, and one that breaks late loses commands.
 */
public final class CommandBook {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Lines a client draws on one page, and the width it wraps at. Both conservative. */
    private static final int LINES_PER_PAGE = 13;
    private static final int CHARS_PER_LINE = 19;

    private final List<CommandNote> notes;
    private final String title;

    public CommandBook(List<CommandNote> notes) {
        this(notes, "Commands");
    }

    public CommandBook(List<CommandNote> notes, String title) {
        this.notes = notes == null ? List.of() : List.copyOf(notes);
        this.title = title == null || title.isBlank() ? "Commands" : title;
    }

    /** For {@code Player#openBook}, which shows it without handing an item over. */
    public Book asBook() {
        return Book.book(mm("<dark_aqua>" + title), mm("<gray>Rain's plugins"), pages());
    }

    /**
     * Every page, in order: a contents page, then one section per plugin.
     *
     * <p>Public so the layout can be measured. An overrunning page is silent, not an error, so the
     * only way to know the book fits is to count it — which is what the test does.
     */
    public List<Component> pages() {
        if (notes.isEmpty()) {
            // Not an empty book: an empty book looks broken, and "nothing has reported" is a real
            // state on a server running Core alone.
            return List.of(mm("<dark_aqua><bold>Commands</bold>\n\n"
                    + "<gray>Nothing on this server has told the directory what it offers yet."));
        }
        List<Component> pages = new ArrayList<>();
        pages.add(contents());
        for (Map.Entry<String, List<CommandNote>> section : bySection().entrySet()) {
            pages.addAll(spread(section.getKey(), section.getValue()));
        }
        return List.copyOf(pages);
    }

    /** The first page: what is in the book, and how many commands each plugin brings. */
    private Component contents() {
        Component page = mm("<dark_aqua><bold>Commands</bold>\n")
                .append(mm("<dark_gray>" + notes.size() + " command(s) you may use\n\n"));
        for (Map.Entry<String, List<CommandNote>> section : bySection().entrySet()) {
            page = page.append(mm("<black>" + section.getKey()))
                    .append(mm("  <dark_gray>" + section.getValue().size() + "\n"));
        }
        return page.append(mm("\n<dark_gray>Click any command to put it in the chat bar."));
    }

    /** One section per plugin, each keeping the directory's order. */
    private Map<String, List<CommandNote>> bySection() {
        Map<String, List<CommandNote>> sections = new LinkedHashMap<>();
        for (CommandNote note : notes) {
            sections.computeIfAbsent(note.plugin(), plugin -> new ArrayList<>()).add(note);
        }
        return sections;
    }

    /**
     * One plugin's commands, over as many pages as they need.
     *
     * <p>A command is never split from its own sentence or its options — the entry is the unit that
     * moves to the next page — because half an entry at a page break reads as a truncation bug.
     */
    private List<Component> spread(String section, List<CommandNote> inSection) {
        Component heading = mm("<dark_aqua><bold>" + section + "</bold>");
        int headingCost = cost(heading) + 1;

        List<Component> pages = new ArrayList<>();
        List<Component> current = new ArrayList<>();
        int used = 0;
        for (CommandNote note : inSection) {
            List<Component> entry = entry(note);
            int entryCost = entry.stream().mapToInt(CommandBook::cost).sum();
            if (used + entryCost + headingCost > LINES_PER_PAGE && !current.isEmpty()) {
                pages.add(join(heading, current, pages.isEmpty()));
                current = new ArrayList<>();
                used = 0;
            }
            current.addAll(entry);
            used += entryCost;
        }
        if (!current.isEmpty()) {
            pages.add(join(heading, current, pages.isEmpty()));
        }
        return pages;
    }

    /** One command: its name, its sentence, and one line per option. */
    private List<Component> entry(CommandNote note) {
        List<Component> lines = new ArrayList<>();
        lines.add(clickable(note.slashed()));
        lines.add(mm("<black>" + escape(note.sentence())));
        for (String option : note.options()) {
            // Indented and dimmer, so the eye can tell an option from the next command down. The
            // option is clickable too, minus its placeholders — typing "/mob pack <creature>" into
            // the chat bar literally is worse than typing "/mob pack ".
            lines.add(mm("<dark_gray>  " + escape(option))
                    .clickEvent(ClickEvent.suggestCommand(typeable(note, option)))
                    .hoverEvent(HoverEvent.showText(mm("<gray>Click to start typing it"))));
        }
        lines.add(Component.empty());
        return lines;
    }

    private static Component clickable(String command) {
        return mm("<blue><underlined>" + escape(command))
                .clickEvent(ClickEvent.suggestCommand(command + " "))
                .hoverEvent(HoverEvent.showText(mm("<gray>Click to put <white>" + escape(command)
                        + "<gray> in the chat bar")));
    }

    /** An option as something a reader can finish: the words up to the first blank. */
    private static String typeable(CommandNote note, String option) {
        StringBuilder typed = new StringBuilder(note.slashed());
        for (String word : option.trim().split("\\s+")) {
            if (word.startsWith("<") || word.startsWith("[")) {
                break;
            }
            typed.append(' ').append(word);
        }
        return typed.append(' ').toString();
    }

    // ─────────────────────────────────────────────────────────────── page arithmetic

    private static Component join(Component heading, List<Component> body, boolean first) {
        Component page = first ? heading : heading.append(mm(" <dark_gray>…"));
        page = page.append(Component.newline()).append(Component.newline());
        for (int index = 0; index < body.size(); index++) {
            page = page.append(body.get(index));
            if (index < body.size() - 1) {
                page = page.append(Component.newline());
            }
        }
        return page;
    }

    /** How many drawn lines one line of text costs, once the client has wrapped it. */
    private static int cost(Component line) {
        int length = PlainTextComponentSerializer.plainText().serialize(line).length();
        return Math.max(1, (length + CHARS_PER_LINE - 1) / CHARS_PER_LINE);
    }

    /**
     * Text a plugin wrote, made safe to put through MiniMessage.
     *
     * <p>A sentence is a value, and every value here came from another plugin's source. One with a
     * stray {@code <} in it would otherwise be parsed as a tag — printed as its own markup at best,
     * and swallowing the rest of the entry at worst.
     */
    private static String escape(String text) {
        return text == null ? "" : MINI.escapeTags(text);
    }

    private static Component mm(String miniMessage) {
        return MINI.deserialize(miniMessage);
    }
}
