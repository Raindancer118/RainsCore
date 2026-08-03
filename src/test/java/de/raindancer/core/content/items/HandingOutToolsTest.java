package de.raindancer.core.content.items;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Handing somebody a tool without also handing them an achievement.
 *
 * <h2>The bug this is about</h2>
 * The no-claim-zone tool is a blaze rod, so an admin clicking "mark somewhere out" was congratulated with
 * <b>Into Fire</b> — the advancement for obtaining a blaze rod, which is a nether milestone. Vanilla's criteria
 * is "this item appeared in your inventory", and it does not care where from.
 *
 * <p>Worth fixing rather than shrugging at. An advancement is a record of something the player did, and one
 * handed out by a menu click devalues every other one on the server — on a server with an achievement feed it
 * also announces it to everybody, which is what the report showed.
 *
 * <h2>Why it is Core's and not the claims module's</h2>
 * Any plugin that gives somebody a tool hits this, and the tool's material is configurable — the claim stick
 * happens to be a golden shovel, which has no advancement, but nothing stops an owner setting it to a blaze rod
 * or an elytra. So the rule belongs where items are handed out, not in each caller.
 *
 * <h2>Only what we caused</h2>
 * The advancement is checked <em>before</em> the item is given and revoked only if it was not already earned.
 * Somebody who found their blaze rod in a fortress last week keeps it. That ordering is the whole correctness
 * argument, and it is what the tests below pin.
 */
class HandingOutToolsTest {

    private static final Path GIVER =
            Path.of("src/main/java/de/raindancer/core/content/items/ToolGift.java");

    private static String source() {
        try {
            return Files.readString(GIVER);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read ToolGift", unreadable);
        }
    }

    @Test
    @DisplayName("there is one place that hands out a tool")
    void theHelperExists() {
        assertThat(GIVER).exists();
        assertThat(source())
                .as("the point of it is that callers stop writing addItem themselves")
                .contains("addItem");
    }

    @Test
    @DisplayName("what the player already earned is read before the item is given")
    void theCheckHappensFirst() {
        String body = source();
        int reads = body.indexOf("isDone()");
        int gives = body.indexOf("addItem(");
        assertThat(reads).as("nothing asks what they had already").isNotNegative();
        assertThat(gives).isNotNegative();
        assertThat(reads)
                .as("read afterwards, everybody looks like they already had it — the item is in the inventory "
                        + "by then and vanilla has granted it")
                .isLessThan(gives);
    }

    @Test
    @DisplayName("an advancement the player had already keeps standing")
    void nothingEarnedIsTakenAway() {
        String body = source();
        int revoke = body.indexOf("revoke(player, advancement)");
        assertThat(revoke).as("nothing is ever revoked").isNotNegative();

        // The revoke has to sit behind the flag that was read before the item was given. Unconditional, it
        // would strip a nether milestone from anybody who earned it properly — worse than the bug.
        assertThat(body.substring(Math.max(0, revoke - 160), revoke))
                .as("the revoke must be guarded by what they had already")
                .contains("!earnedAlready");
    }

    @Test
    @DisplayName("a full inventory drops the tool rather than swallowing it")
    void nothingIsLost() {
        assertThat(source())
                .as("a command that silently does nothing for whoever has a full inventory is a command that "
                        + "looks broken to exactly the people most likely to have one")
                .contains("dropItemNaturally");
    }
}
