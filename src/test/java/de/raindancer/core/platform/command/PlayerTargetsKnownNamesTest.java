package de.raindancer.core.platform.command;

import de.raindancer.core.social.presence.KnownNames;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An offline player Paper's name cache has forgotten — it holds a limited number for a limited time — is still
 * found: suggestions offered their name, and the command then said it had never seen them.
 */
class PlayerTargetsKnownNamesTest {

    @TempDir
    Path folder;

    private final Server server = mock(Server.class);
    private final UUID ann = UUID.randomUUID();

    @AfterEach
    void reset() {
        PlayerTargets.useKnownNames(null);
    }

    @Test
    @DisplayName("a name only Core still remembers resolves to that player, any case")
    void fromKnownNames() {
        KnownNames names = new KnownNames(folder.resolve("known-names.yml"));
        names.seen(ann, "AnnTheBuilder");
        PlayerTargets.useKnownNames(names);
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getUniqueId()).thenReturn(ann);
        when(server.getOfflinePlayer(ann)).thenReturn(offline);

        assertThat(PlayerTargets.lookup(server, null, "annthebuilder").single()).contains(offline);
        assertThat(PlayerTargets.find(server, "AnnTheBuilder")).contains(offline);
        assertThat(PlayerTargets.isRealName(server, "ANNTHEBUILDER")).isTrue();
    }

    @Test
    @DisplayName("without Core's names, the server's own player files are searched by name")
    void fromPlayerFiles() {
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getUniqueId()).thenReturn(ann);
        when(offline.getName()).thenReturn("AnnTheBuilder");
        when(server.getOfflinePlayers()).thenReturn(new OfflinePlayer[]{offline});

        assertThat(PlayerTargets.lookup(server, null, "annthebuilder").single()).contains(offline);
    }

    @Test
    @DisplayName("a renamed player is found under the new name; the old one then means nobody")
    void renames() {
        KnownNames names = new KnownNames(folder.resolve("known-names.yml"));
        names.seen(ann, "OldName");
        names.seen(ann, "NewName");
        assertThat(names.idOf("newname")).contains(ann);
        assertThat(names.idOf("OldName")).isEmpty();
        assertThat(names.save()).isTrue();

        KnownNames again = new KnownNames(folder.resolve("known-names.yml"));
        again.load();
        assertThat(again.idOf("NewName")).contains(ann);
        assertThat(again.nameOf(ann)).contains("NewName");
    }

    @Test
    @DisplayName("a name that passed to somebody else points at whoever had it last")
    void nameTakenOver() {
        KnownNames names = new KnownNames(folder.resolve("known-names.yml"));
        UUID bob = UUID.randomUUID();
        names.seen(ann, "Shared");
        names.seen(bob, "Shared");
        assertThat(names.idOf("shared")).contains(bob);
        assertThat(names.nameOf(ann)).isEmpty();
    }

    @Test
    @DisplayName("a stale name from an old player file never takes a name somebody holds now")
    void seedingNeverStealsAName() {
        KnownNames names = new KnownNames(folder.resolve("known-names.yml"));
        UUID bob = UUID.randomUUID();
        names.seen(bob, "Shared");                 // Bob joined under it recently
        names.seenIfUnknown(ann, "Shared");        // Ann's old file still says it

        assertThat(names.idOf("shared")).contains(bob);
        assertThat(names.nameOf(bob)).contains("Shared");
        assertThat(names.nameOf(ann)).isEmpty();
    }
}
