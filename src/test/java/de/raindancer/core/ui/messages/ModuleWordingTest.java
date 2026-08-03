package de.raindancer.core.ui.messages;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A module handing its own wording to the one {@link Messages} everybody shares.
 *
 * <h2>Why this exists</h2>
 * A module is not a plugin. It has no data folder of its own and no {@code messages.yml} on disk — it ships
 * its wording inside its jar and runs on the host's {@code Messages}. Without a way to hand that wording
 * over, every key the module uses is a key nobody has, and the player gets the key itself back.
 *
 * <p>That is not hypothetical. The claims module shipped with a full {@code messages.yml} in its jar and
 * nothing that read it, so {@code /claim} answered <code>[Core] claim.nonehere</code> — Core's prefix,
 * because the module's own was never loaded either, and the raw key, because nothing defined it.
 *
 * <h2>The layer it lands on</h2>
 * A module's wording is a <b>floor</b>, the same level as {@link Messages#define}: it fills a key nobody
 * else has and loses to everything else. That ordering is the whole point —
 *
 * <ul>
 *   <li>the <b>owner's file wins</b>, or a module could silently ignore what somebody wrote;</li>
 *   <li>the <b>host's bundled file wins too</b>, so a host that folds a module in can reword it without
 *       touching the module — which is exactly how RainsSMPCore differs from the standalone plugin;</li>
 *   <li>and two modules that both define a key do not fight: first one in wins, and neither can overwrite
 *       wording that came from a file.</li>
 * </ul>
 */
class ModuleWordingTest {

    @TempDir
    Path folder;

    private static final String BUNDLED_BY_THE_MODULE = """
            prefix: "<gray>[Claims] "
            claim:
              none-here: "You are not standing in a claim."
              created: "Claimed <claim>."
            error:
              world-disabled: "Not in this world."
            """;

    private static InputStream module() {
        return new ByteArrayInputStream(BUNDLED_BY_THE_MODULE.getBytes(StandardCharsets.UTF_8));
    }

    private Messages fresh() {
        Messages messages = new Messages(folder.resolve("messages.yml"));
        messages.load(null);
        return messages;
    }

    @Test
    @DisplayName("a key only the module has is answered with the module's wording")
    void theModulesOwnKeysWork() {
        Messages messages = fresh();

        assertThat(messages.defineFrom(module())).isEqualTo(4);
        assertThat(messages.raw("claim.none-here")).isEqualTo("You are not standing in a claim.");
    }

    @Test
    @DisplayName("nested keys arrive flattened, not as sections")
    void nestingIsFlattened() {
        Messages messages = fresh();
        messages.defineFrom(module());

        assertThat(messages.raw("error.world-disabled")).isEqualTo("Not in this world.");
        assertThat(messages.has("claim.created")).isTrue();
    }

    @Test
    @DisplayName("the host's own bundled wording beats the module's")
    void theHostCanRewordAModule() throws IOException {
        // How a host that folds a module in changes its wording without touching the module.
        Messages messages = new Messages(folder.resolve("messages.yml"));
        messages.load(new ByteArrayInputStream(
                "claim:\n  none-here: \"The host's version.\"\n".getBytes(StandardCharsets.UTF_8)));
        messages.defineFrom(module());

        assertThat(messages.raw("claim.none-here"))
                .as("a module must not be able to overrule wording that came from a file")
                .isEqualTo("The host's version.");
    }

    @Test
    @DisplayName("what the owner wrote beats the module by a mile")
    void theOwnerAlwaysWins() throws IOException {
        Path file = folder.resolve("messages.yml");
        Files.writeString(file, "claim:\n  none-here: \"Mine.\"\n");

        Messages messages = new Messages(file);
        messages.load(null);
        messages.defineFrom(module());

        assertThat(messages.raw("claim.none-here")).isEqualTo("Mine.");
    }

    @Test
    @DisplayName("the first module to define a key keeps it")
    void twoModulesDoNotFight() {
        Messages messages = fresh();
        messages.defineFrom(module());
        messages.defineFrom(new ByteArrayInputStream(
                "claim:\n  none-here: \"Somebody else's.\"\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(messages.raw("claim.none-here"))
                .as("a module loaded later must not silently reword one loaded earlier")
                .isEqualTo("You are not standing in a claim.");
    }

    @Test
    @DisplayName("no stream is nothing to do, not a crash")
    void anAbsentResourceIsSurvivable() {
        // getResourceAsStream returns null for a file that is not in the jar, which is a build mistake
        // rather than a reason to take the server down.
        assertThat(fresh().defineFrom(null)).isZero();
    }

    @Test
    @DisplayName("wording that will not parse costs the module its wording and nothing else")
    void abrokenBundleIsContained() {
        Messages messages = fresh();
        messages.defineFrom(new ByteArrayInputStream(
                "claim:\n  : : :\n  \"unclosed\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(messages.problems())
                .as("it is the module's bug, and the owner needs to be told which module")
                .isNotEmpty();
    }
}
