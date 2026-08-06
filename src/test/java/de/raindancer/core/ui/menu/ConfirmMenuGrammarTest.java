package de.raindancer.core.ui.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one confirmation dialog on the server, and the arrangement of its two answers.
 *
 * <h2>Why this is checked against the source rather than driven</h2>
 * Because what matters about it cannot be asserted by clicking it. {@code No} on the left and
 * {@code Yes} on the right is a habit people build, and a dialog that swaps them somewhere is a
 * dialog people learn to click through and then get wrong exactly once — on the page that deletes
 * something. Drawing a menu needs a server; reading which column each answer is in does not.
 *
 * <p>This existed three times before it was moved here — in the claims module, in the moderation
 * module and in the warps module — each with the columns the same and the wording slightly
 * different. Three copies is three places to fix the next thing in one of, and the one nobody fixes
 * is always the one on the page that deletes something.
 */
class ConfirmMenuGrammarTest {

    private static final Path SOURCE =
            Path.of("src/main/java/de/raindancer/core/ui/menu/ConfirmMenu.java");

    private static String source() {
        try {
            return Files.readString(SOURCE);
        } catch (IOException unreadable) {
            throw new AssertionError("the one confirmation dialog is gone", unreadable);
        }
    }

    @Test
    @DisplayName("the scan reads it, so a rename cannot quietly empty this")
    void theScanIsNotVacuous() {
        assertThat(source()).contains("class ConfirmMenu");
    }

    @Test
    @DisplayName("No is on the left and Yes is on the right")
    void theAnswersAreWhereTheHabitExpectsThem() {
        String body = source();
        int no = body.indexOf("RED_CONCRETE");
        int yes = body.indexOf("LIME_CONCRETE");

        assertThat(no).as("the refusing answer is gone").isNotNegative();
        assertThat(yes).as("the confirming answer is gone").isNotNegative();
        assertThat(no)
                .as("No comes first in the file because it is drawn on the left, and every "
                        + "confirmation on this server has to agree about that")
                .isLessThan(yes);
    }

    @Test
    @DisplayName("it is a three-row dialog, not a six-row page")
    void itIsADialog() {
        // A question with two answers on a full page reads as an empty page with two buttons lost
        // in it.
        assertThat(source()).contains("parent, 3");
    }

    @Test
    @DisplayName("saying no does nothing but go back")
    void noIsAlwaysHarmless() {
        String body = source();
        int no = body.indexOf("RED_CONCRETE");
        String refusing = body.substring(no, body.indexOf("LIME_CONCRETE"));

        assertThat(refusing)
                .as("the No button runs something other than leaving the page")
                .contains("leave()");
        assertThat(refusing)
                .as("nothing on the No path may run what was being confirmed")
                .doesNotContain("onYes");
    }

    @Test
    @DisplayName("the middle button says what the yes would do")
    void theConsequencesAreShown() {
        // The whole reason this is a page and not a chat prompt: the thing being confirmed is on
        // screen, so somebody who opened the wrong warp's page sees the wrong warp's name here.
        assertThat(source()).contains("consequences");
    }

    @Test
    @DisplayName("confirming leaves the dialog, so a click is visibly answered")
    void yesGoesSomewhere() {
        // Reported from a live server: "I click it and nothing happens." The action ran — it always did — and
        // the dialog stayed on screen with no sign it had, so the only feedback was pressing it again.
        //
        // It went unnoticed in the other plugins by luck: their callbacks open a page of their own, which
        // replaces the dialog and looks like an answer. The Hunger Games' callbacks call refresh() on the page
        // underneath, which redraws an inventory nobody is looking at — so there was nothing to see at all.
        //
        // Leaving is the dialog's own business, not the caller's. A caller that has to remember to close the
        // thing that called it is a caller that will not.
        String written = source();

        assertThat(written)
                .as("after onYes there is nothing: the dialog stays open and a click has no visible answer")
                .contains("backToWhoeverOpenedThis()");
    }

    @Test
    @DisplayName("the action still runs before the dialog goes away")
    void theActionComesFirst() {
        // Order matters and is easy to get backwards. Reopening the parent first would redraw it from state
        // the action has not changed yet, so the page would show the old world and only agree with reality
        // after the next click.
        String written = source();
        int runsIt = written.indexOf("onYes.run()");
        int leaves = written.indexOf("backToWhoeverOpenedThis()");

        assertThat(runsIt).as("onYes.run() is gone").isGreaterThan(0);
        assertThat(leaves)
                .as("the parent must be reopened after the action, or it redraws stale state")
                .isGreaterThan(runsIt);
    }
}
