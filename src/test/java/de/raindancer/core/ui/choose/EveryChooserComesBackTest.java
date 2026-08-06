package de.raindancer.core.ui.choose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That picking something puts you back where you were.
 *
 * <h2>The bug this was written for, reported from a live server</h2>
 * "The screens do not close on a click and do not go back." Six choosers, and only
 * {@link AmountChooser} reopened the page that had opened it. The other five called the caller's
 * callback and either closed the inventory or — {@code PlayerChooser} — left themselves open, so
 * choosing a player from a list did nothing visible: the list stayed on screen, and the page that
 * wanted the answer was never seen again.
 *
 * <p>The plugins were papering over it. A callback would end with {@code refresh()}, which redraws
 * a menu that is not the one being looked at, and every one of them had to know to do it. That is a
 * convention rather than a mechanism, and five of six choosers proved the convention does not hold.
 *
 * <h2>Why a source scan</h2>
 * A chooser cannot be opened without a server, so there is nothing to assert against. What can be
 * read is whether each one goes back — and this is a rule about how the framework behaves, which is
 * exactly the kind of thing that goes quietly wrong in the sixth copy.
 */
class EveryChooserComesBackTest {

    private static final Path SOURCES = Path.of("src/main/java/de/raindancer/core/ui/choose");

    /**
     * The one-shot choosers: a page that takes a single answer and hands it back.
     *
     * <p>{@code FlagChooser} is deliberately not one. It toggles values in place and stays open, which is
     * right for a page somebody sets six things on — going back after every click would make setting six
     * flags six round trips. The rule below is about answering a question, not about changing something.
     *
     * <p>Told apart by whether the page holds a one-shot callback rather than by name, because a name is what
     * the next chooser will get wrong.
     */
    private static List<Path> choosers() {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(path -> path.toString().endsWith("Chooser.java"))
                    .filter(EveryChooserComesBackTest::handsOneAnswerBack)
                    .sorted().toList();
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + SOURCES, unreadable);
        }
    }

    /** Whether that page's whole job is to answer once. */
    private static boolean handsOneAnswerBack(Path chooser) {
        String code = withoutComments(read(chooser));
        return code.contains("chosen.accept(") || code.contains("onAccept.accept(");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + file, unreadable);
        }
    }

    @Test
    @DisplayName("the scan finds the choosers, so it cannot pass by finding none")
    void theScanIsNotVacuous() {
        assertThat(choosers())
                .as("seven of the eight pages in this package answer a question once; a scan that found none "
                        + "would make every rule below vacuous")
                .hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("every chooser goes back to the page that opened it")
    void theyAllComeBack() {
        List<String> dead = new ArrayList<>();
        for (Path chooser : choosers()) {
            String source = read(chooser);
            // Either through the shared helper or by opening the parent directly — what matters is that
            // something reopens it, not which spelling was used.
            boolean comesBack = source.contains("backToWhoeverOpenedThis()")
                    || source.contains("parent().open()")
                    || source.contains("parent.open()");
            if (!comesBack) {
                dead.add(chooser.getFileName().toString());
            }
        }
        assertThat(dead)
                .as("choosing something in one of these leaves the viewer looking at nothing, or at the "
                        + "list they just chose from — and the page that wanted the answer is never seen "
                        + "again. Five of six did this")
                .isEmpty();
    }

    @Test
    @DisplayName("no chooser just closes the inventory and walks away")
    void noneOfThemAbandonTheViewer() {
        List<String> abandoning = new ArrayList<>();
        for (Path chooser : choosers()) {
            String code = withoutComments(read(chooser));
            // closeInventory is legitimate as the fallback when there is no parent — inside
            // backToWhoeverOpenedThis. On its own, in a chooser, it is the bug.
            boolean closesDirectly = code.contains("closeInventory()");
            boolean alsoComesBack = code.contains("backToWhoeverOpenedThis()");
            if (closesDirectly && !alsoComesBack) {
                abandoning.add(chooser.getFileName().toString());
            }
        }
        assertThat(abandoning)
                .as("a chooser that closes the window is a chooser that answers a question and then hides "
                        + "the page that asked it")
                .isEmpty();
    }

    /** Source with comments stripped, so prose about the bug is not read as the bug. */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "")
                .lines()
                .map(line -> {
                    int slashes = line.indexOf("//");
                    return slashes < 0 ? line : line.substring(0, slashes);
                })
                .reduce("", (all, line) -> all + line + "\n");
    }
}
