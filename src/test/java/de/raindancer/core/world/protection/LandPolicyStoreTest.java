package de.raindancer.core.world.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeping what an admin decided about the flags.
 *
 * <h2>Why this has to exist</h2>
 * {@link LandPolicies} was built in memory from {@link LandFlag#builtInDefault()} and nothing ever read a
 * file into it or wrote one out. An admin who turned a flag off got exactly what they asked for until the
 * next restart, which is worse than not offering the setting: the server behaves differently after a
 * restart than it did before one, and nothing says why.
 *
 * <p>The old standalone plugin held this in its {@code config.yml} under {@code flags:}, so that shape has
 * to be readable — an upgrading server has one and it is the whole record of what its admin decided.
 *
 * <h2>Only what was changed</h2>
 * A file spelling out all twenty-eight flags at their built-in values is a file nobody can read for the two
 * lines that matter, and it freezes the built-in defaults: change one in a release and every existing server
 * keeps the old value forever, because their file names it explicitly. So the file holds differences only.
 */
class LandPolicyStoreTest {

    @TempDir
    Path folder;

    private Path file() {
        return folder.resolve("land-flags.yml");
    }

    @Test
    @DisplayName("an untouched policy writes no file at all")
    void nothingChangedIsNoFile() throws IOException {
        new LandPolicyStore(file()).save(LandPolicies.builtIn());

        assertThat(file())
                .as("a file full of built-in values is one nobody can read for what actually changed")
                .doesNotExist();
    }

    @Test
    @DisplayName("a policy and a default survive a restart")
    void whatWasSetComesBack() throws IOException {
        LandPolicies policies = LandPolicies.builtIn();
        policies.policy(LandFlag.PVP, FlagPolicy.FORCED_OFF);
        policies.flagDefault(LandFlag.EXPLOSIONS, !LandFlag.EXPLOSIONS.builtInDefault());

        new LandPolicyStore(file()).save(policies);
        LandPolicies loaded = new LandPolicyStore(file()).load();

        assertThat(loaded.policy(LandFlag.PVP)).isEqualTo(FlagPolicy.FORCED_OFF);
        assertThat(loaded.flagDefault(LandFlag.EXPLOSIONS))
                .isEqualTo(!LandFlag.EXPLOSIONS.builtInDefault());
    }

    @Test
    @DisplayName("a flag nobody touched still answers with its built-in value")
    void theRestIsUntouched() throws IOException {
        LandPolicies policies = LandPolicies.builtIn();
        policies.policy(LandFlag.PVP, FlagPolicy.DISABLED);
        new LandPolicyStore(file()).save(policies);

        LandPolicies loaded = new LandPolicyStore(file()).load();

        assertThat(loaded.policy(LandFlag.EXPLOSIONS)).isEqualTo(FlagPolicy.AVAILABLE);
        assertThat(loaded.flagDefault(LandFlag.EXPLOSIONS)).isEqualTo(LandFlag.EXPLOSIONS.builtInDefault());
    }

    @Test
    @DisplayName("no file is every flag as it ships")
    void anAbsentFileIsFine() {
        LandPolicies loaded = new LandPolicyStore(file()).load();

        assertThat(loaded.isUntouched()).isTrue();
    }

    @Test
    @DisplayName("the old plugin's flags: block is read as it was written")
    void theStandaloneConfigStillReads() throws IOException {
        // Exactly the shape Rain's Extended Claims wrote — flags.<key>.policy and flags.<key>.default.
        // An upgrading server has this and it is the only record of what its admin decided.
        Files.writeString(file(), """
                flags:
                  pvp:
                    policy: forced-off
                    default: false
                  explosions:
                    policy: available
                    default: true
                """);

        LandPolicies loaded = new LandPolicyStore(file()).load();

        assertThat(loaded.policy(LandFlag.PVP)).isEqualTo(FlagPolicy.FORCED_OFF);
        assertThat(loaded.flagDefault(LandFlag.EXPLOSIONS)).isTrue();
    }

    @Test
    @DisplayName("a flag the server no longer has is ignored rather than fatal")
    void anUnknownFlagIsSkipped() throws IOException {
        Files.writeString(file(), """
                flags:
                  something-we-removed:
                    policy: disabled
                  pvp:
                    policy: forced-on
                """);

        LandPolicies loaded = new LandPolicyStore(file()).load();

        assertThat(loaded.policy(LandFlag.PVP))
                .as("one stale key must not cost the admin every other decision in the file")
                .isEqualTo(FlagPolicy.FORCED_ON);
    }

    @Test
    @DisplayName("a policy word nobody recognises leaves that flag alone")
    void anUnreadablePolicyIsNotGuessed() throws IOException {
        Files.writeString(file(), "flags:\n  pvp:\n    policy: sometimes\n");

        assertThat(new LandPolicyStore(file()).load().policy(LandFlag.PVP))
                .as("guessing at a protection setting is how a server ends up unprotected quietly")
                .isEqualTo(FlagPolicy.AVAILABLE);
    }

    @Test
    @DisplayName("setting a flag back to its built-in value removes it from the file")
    void undoingAChangeShrinksTheFile() throws IOException {
        LandPolicies policies = LandPolicies.builtIn();
        policies.policy(LandFlag.PVP, FlagPolicy.DISABLED);
        LandPolicyStore store = new LandPolicyStore(file());
        store.save(policies);

        policies.policy(LandFlag.PVP, FlagPolicy.AVAILABLE);
        store.save(policies);

        assertThat(store.load().isUntouched())
                .as("an admin who undoes a change should not leave a line behind that says they did")
                .isTrue();
    }

    @Test
    @DisplayName("a broken file is not silently treated as an empty one")
    void abrokenFileIsReported() throws IOException {
        Files.writeString(file(), "flags:\n  : : :\n  \"unclosed\n");

        LandPolicyStore store = new LandPolicyStore(file());
        LandPolicies loaded = store.load();

        assertThat(loaded.isUntouched()).isTrue();
        assertThat(store.problem())
                .as("silently running with every flag at its default is how a server loses its protection "
                        + "settings without anybody noticing")
                .isPresent();
    }
}
