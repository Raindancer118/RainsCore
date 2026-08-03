package de.raindancer.core.ui.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An empty list that tells you what to do should let you do it.
 *
 * <h2>Why this exists</h2>
 * Reported plainly: "there's a stick telling me that I have no claims yet in the claim selection screen, but I
 * can't click it to get a claim stick". The empty-state icon was decorative — placed with no handler at all — so
 * it named the command to type and then ignored the one gesture the player had already made.
 *
 * <p>That is worse than an unhelpful message. A player who clicks a button that says "start one with /claim new"
 * and gets nothing has been told the screen is broken, not that they should go and type something. Everything
 * else on the page is clickable, so the one thing that is not reads as a bug.
 *
 * <p>Fixed in the framework rather than in the one screen: every paginated list here has an empty state, most of
 * them name the way out of it, and none of them could act on it. A hook costs one method and stays opt-in — a
 * list with nothing sensible to offer simply does not override it, and behaves exactly as it did.
 */
class EmptyStateActionTest {

    private static final Path PAGINATED =
            Path.of("src/main/java/de/raindancer/core/ui/menu/PaginatedMenu.java");

    private static String source() {
        try {
            return Files.readString(PAGINATED);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read PaginatedMenu", unreadable);
        }
    }

    @Test
    @DisplayName("the scan found the class, so a rename cannot quietly empty this")
    void theScanIsNotVacuous() {
        assertThat(source()).contains("emptyIcon");
    }

    @Test
    @DisplayName("the empty-state icon is placed with its handler, not on its own")
    void theEmptyIconCanBeClicked() {
        String body = source();
        int at = body.indexOf("if (all.isEmpty())");
        assertThat(at).as("the empty branch is gone").isNotNegative();

        String branch = body.substring(at, Math.min(body.length(), at + 500));
        assertThat(branch)
                .as("placing the icon without a handler is what made it look broken when clicked")
                .contains("emptyAction");
    }

    @Test
    @DisplayName("a list with nothing to offer is unchanged")
    void theHookIsOptional() {
        String body = source();
        int at = body.indexOf("protected void emptyAction(");
        assertThat(at).as("there is no hook at all").isNotNegative();

        // The default has to do nothing, so every existing list keeps its current behaviour and only the
        // screens that have something to offer opt in.
        String method = body.substring(at, body.indexOf("}", at) + 1);
        assertThat(method.replaceAll("\\s+", ""))
                .as("a default that did something would change every list in every plugin at once")
                .endsWith("{}");
    }
}
