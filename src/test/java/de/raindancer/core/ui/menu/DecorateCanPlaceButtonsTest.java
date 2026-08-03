package de.raindancer.core.ui.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a button asked for from {@code decorate()} is actually drawn.
 *
 * <h2>The trap this closes</h2>
 * {@code band()}, {@code toolbar()} and {@code cell()} do not place anything themselves — they buffer, and
 * {@code layoutBands()} writes the buffer out afterwards so each band can be centred once the page has finished
 * deciding what is in it. The buffer was flushed <em>before</em> {@code decorate()} ran.
 *
 * <p>So a {@code toolbar()} call made from {@code decorate()} went into the buffer and was never written. No
 * error, no log line, no missing method — the button simply was not there. Found when a blaze rod added to the
 * no-claim-zone list did not appear, and it would have caught the next person exactly the same way, because
 * {@code decorate()} is the obvious place to put chrome that does not depend on the page's contents.
 *
 * <p>Flushed again after {@code decorate()}, and the buffer clears as it is written so nothing is placed twice.
 */
class DecorateCanPlaceButtonsTest {

    private static final Path MENU = Path.of("src/main/java/de/raindancer/core/ui/menu/Menu.java");

    private static String source() {
        try {
            return Files.readString(MENU);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read Menu", unreadable);
        }
    }

    @Test
    @DisplayName("the scan found the pipeline, so a rename cannot quietly empty this")
    void theScanIsNotVacuous() {
        assertThat(source()).contains("layoutBands();").contains("decorate();");
    }

    @Test
    @DisplayName("the buffer is written after decorate, not only before it")
    void whatDecorateAsksForIsDrawn() {
        String body = source();
        int decorate = body.indexOf("        decorate();");
        assertThat(decorate).as("the decorate step is gone").isNotNegative();

        int flushAfter = body.indexOf("layoutBands();", decorate);
        int chrome = body.indexOf("paintChrome();", decorate);
        assertThat(flushAfter)
                .as("a toolbar() call from decorate() is buffered; without a flush after it, it is never drawn "
                        + "and nothing says so")
                .isNotNegative();
        assertThat(flushAfter)
                .as("and it has to happen before the chrome, which owns the bottom row")
                .isLessThan(chrome);
    }

    @Test
    @DisplayName("the buffer empties as it is written, so nothing lands twice")
    void flushingTwiceIsSafe() {
        String body = source();
        int at = body.indexOf("private void layoutBands()");
        assertThat(at).isNotNegative();

        String method = body.substring(at, body.indexOf("\n    }", at));
        assertThat(method)
                .as("flushed twice per render now: without clearing, every buffered button would be placed "
                        + "again and a band of three would try to centre six")
                .contains("clear()");
    }
}
