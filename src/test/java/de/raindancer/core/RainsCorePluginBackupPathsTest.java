package de.raindancer.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link RainsCorePlugin#backupsDirectoryFor} / {@link RainsCorePlugin#pluginsDirectoryFor} — the
 * fix for a real-server-only NullPointerException an actual Paper boot found and no unit test did.
 *
 * <p>{@code getDataFolder()} hands back a <em>relative</em> path ({@code plugins/RainsCore}) on a
 * real server, because it is relative to the server's own working directory. Every test before
 * this one built its {@code dataFolder} from an absolute {@code @TempDir}, so {@code getParent()}
 * chaining twice always had somewhere to land — on a relative path it does not, and the second
 * {@code getParent()} on a one-segment relative path returns {@code null}. These tests are
 * deliberately built from a bare relative {@code Path.of("plugins", "RainsCore")}, the one shape
 * every previous test never used, to pin exactly that gap shut.
 */
class RainsCorePluginBackupPathsTest {

    private static final Path RELATIVE_DATA_FOLDER = Path.of("plugins", "RainsCore");

    @Test
    void backupsDirectoryNeverThrowsOnARelativeDataFolder() {
        assertThatCode(() -> RainsCorePlugin.backupsDirectoryFor(RELATIVE_DATA_FOLDER))
                .doesNotThrowAnyException();
    }

    @Test
    void backupsDirectoryIsASiblingOfPluginsNotInsideIt() {
        Path backups = RainsCorePlugin.backupsDirectoryFor(RELATIVE_DATA_FOLDER);
        assertThat(backups).isAbsolute();
        assertThat(backups.endsWith(Path.of("backups", "rainscore"))).isTrue();
        assertThat(backups.toString()).doesNotContain("plugins" + java.io.File.separator + "backups");
    }

    @Test
    void pluginsDirectoryNeverThrowsOnARelativeDataFolder() {
        assertThatCode(() -> RainsCorePlugin.pluginsDirectoryFor(RELATIVE_DATA_FOLDER))
                .doesNotThrowAnyException();
    }

    @Test
    void pluginsDirectoryIsTheDataFoldersAbsoluteParent() {
        Path plugins = RainsCorePlugin.pluginsDirectoryFor(RELATIVE_DATA_FOLDER);
        assertThat(plugins).isAbsolute();
        assertThat(plugins.endsWith(Path.of("plugins"))).isTrue();
    }

    @Test
    void anAbsoluteDataFolderBehavesTheSameWay() {
        Path absolute = Path.of("/srv/minecraft/plugins/RainsCore");
        assertThat(RainsCorePlugin.backupsDirectoryFor(absolute))
                .isEqualTo(Path.of("/srv/minecraft/backups/rainscore"));
        assertThat(RainsCorePlugin.pluginsDirectoryFor(absolute))
                .isEqualTo(Path.of("/srv/minecraft/plugins"));
    }
}
