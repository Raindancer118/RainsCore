package de.raindancer.core.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule behind a real incident: a join handler that mutates a player's inventory based only on
 * "is the feature in the right state" — never "is this player actually in the place the feature is
 * about" — fires on every join anywhere on the server the moment the state happens to be right, which
 * for most features is most of the time. A player lost real gear this way, on a join into their
 * server's own ordinary world, because the one join handler that cleared inventories never asked
 * which world they had landed in.
 *
 * <h2>Why a source scan, and what it actually checks</h2>
 * There is nothing to construct and assert against — the bug is an absence, not a value — so this
 * reads the source of every {@code @EventHandler} method taking a {@code PlayerJoinEvent} and demands
 * that any one of them touching an inventory (calling {@code getInventory().clear(}, or a method
 * literally named {@code give(} — this codebase's own established name for "clear and hand over a
 * kit", see {@code SpeedrunLobbyItems.give} and {@code ToolGift.give}) also mentions {@code getWorld()}
 * somewhere in the same method. That is a heuristic, not a proof: it cannot tell a real location
 * check from a comment that happens to contain the words, and it says nothing about a handler for any
 * other event. It catches the one shape of mistake that already happened once, which is the bar this
 * kind of test is for — see {@code MenuGrammarTest} and {@code EveryChooserComesBackTest} for the
 * same reasoning applied elsewhere in this codebase.
 */
class JoinHandlersCheckLocationTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern HANDLER_METHOD = Pattern.compile(
            "@EventHandler[^)]*\\)\\s*\\n\\s*public\\s+void\\s+\\w+\\(\\s*PlayerJoinEvent\\s+\\w+\\s*\\)\\s*\\{");

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** The body of one method: from its opening brace to the matching closing one, by depth-counting. */
    private static String bodyFrom(String source, int openBraceIndex) {
        int depth = 0;
        for (int i = openBraceIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(openBraceIndex, i + 1);
                }
            }
        }
        return source.substring(openBraceIndex);   // unterminated — treat the rest of the file as the body
    }

    @Test
    @DisplayName("the scan actually finds PlayerJoinEvent handlers, so it cannot pass by finding none")
    void theScanIsNotVacuous() throws IOException {
        int found = 0;
        for (Path file : javaSources()) {
            Matcher matcher = HANDLER_METHOD.matcher(Files.readString(file));
            while (matcher.find()) {
                found++;
            }
        }
        assertThat(found).as("no @EventHandler PlayerJoinEvent method was found anywhere — the scan "
                        + "below would vacuously pass no matter what any of them did")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("every PlayerJoinEvent handler that touches an inventory also checks the world")
    void everyJoinHandlerThatTouchesAnInventoryChecksTheWorld() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaSources()) {
            String source = Files.readString(file);
            Matcher matcher = HANDLER_METHOD.matcher(source);
            while (matcher.find()) {
                String body = bodyFrom(source, matcher.end() - 1);
                boolean touchesInventory = body.contains("getInventory().clear(") || body.contains(".give(");
                boolean checksWorld = body.contains("getWorld()");
                if (touchesInventory && !checksWorld) {
                    offenders.add(SOURCES.relativize(file).toString());
                }
            }
        }
        assertThat(offenders)
                .as("a PlayerJoinEvent handler that clears or replaces an inventory without checking "
                        + "which world the player joined into fires on every join anywhere on the "
                        + "server the moment its feature's state happens to be right — which for most "
                        + "features is most of the time. This is exactly the bug that lost a player "
                        + "their gear: see JoinHandlersCheckLocationTest's class javadoc.")
                .isEmpty();
    }
}
