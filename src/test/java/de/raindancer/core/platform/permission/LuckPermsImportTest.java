package de.raindancer.core.platform.permission;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Moving grants.yml into LuckPerms: the old file is only put aside once LuckPerms has actually kept
 * what it was given. LuckPerms saves asynchronously, and a save it refuses used to leave the file
 * renamed, the import never retried, and the permissions nowhere.
 */
class LuckPermsImportTest {

    @TempDir
    Path folder;

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");

    private final LuckPerms luckPerms = mock(LuckPerms.class);
    private final UserManager users = mock(UserManager.class);

    private org.mockito.MockedStatic<net.luckperms.api.LuckPermsProvider> provider;

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        provider.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        // PermissionNode.builder asks the static provider; outside a server there is none.
        net.luckperms.api.node.types.PermissionNode.Builder builder =
                mock(net.luckperms.api.node.types.PermissionNode.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(builder.build()).thenReturn(mock(net.luckperms.api.node.types.PermissionNode.class));
        net.luckperms.api.node.NodeBuilderRegistry registry = mock(net.luckperms.api.node.NodeBuilderRegistry.class);
        when(registry.forPermission()).thenReturn(builder);
        when(luckPerms.getNodeBuilderRegistry()).thenReturn(registry);
        provider = org.mockito.Mockito.mockStatic(net.luckperms.api.LuckPermsProvider.class);
        provider.when(net.luckperms.api.LuckPermsProvider::get).thenReturn(luckPerms);

        User user = mock(User.class);
        NodeMap data = mock(NodeMap.class);
        DataMutateResult added = mock(DataMutateResult.class);
        when(added.wasSuccessful()).thenReturn(true);
        when(data.add(any(Node.class))).thenReturn(added);
        when(user.data()).thenReturn(data);
        when(luckPerms.getUserManager()).thenReturn(users);
        when(users.getUser(ALEX)).thenReturn(user);
        Files.writeString(folder.resolve("grants.yml"), "granted:\n  " + ALEX + ":\n    - some.node\n");
    }

    @Test
    @DisplayName("a save LuckPerms refused leaves grants.yml where it was, to be imported again")
    void aRefusedSaveKeepsTheFile() {
        when(users.saveUser(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("storage")));

        new LuckPermsGrantStore(folder, luckPerms).importFromLocalIfPresent(folder.resolve("grants.yml"));

        assertThat(folder.resolve("grants.yml")).exists();
        assertThat(folder.resolve("grants.yml.imported-into-luckperms")).doesNotExist();
    }

    @Test
    @DisplayName("a save LuckPerms kept puts the old file aside")
    void aKeptSaveMovesTheFile() {
        when(users.saveUser(any())).thenReturn(CompletableFuture.completedFuture(null));

        new LuckPermsGrantStore(folder, luckPerms).importFromLocalIfPresent(folder.resolve("grants.yml"));

        assertThat(folder.resolve("grants.yml")).doesNotExist();
        assertThat(folder.resolve("grants.yml.imported-into-luckperms")).exists();
    }
}
