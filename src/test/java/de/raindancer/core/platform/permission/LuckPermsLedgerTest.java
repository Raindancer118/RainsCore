package de.raindancer.core.platform.permission;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilderRegistry;
import net.luckperms.api.node.types.PermissionNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ledger is what makes LuckPerms safe to share: Core only ever takes away what Core itself gave.
 * A node an admin set by hand — before or after — is theirs.
 */
class LuckPermsLedgerTest {

    @TempDir
    Path folder;

    private static final UUID ALEX = UUID.randomUUID();
    private final LuckPerms luckPerms = mock(LuckPerms.class);
    private final NodeMap data = mock(NodeMap.class);
    private MockedStatic<LuckPermsProvider> provider;

    @BeforeEach
    void setUp() {
        PermissionNode.Builder builder = mock(PermissionNode.Builder.class, Mockito.RETURNS_SELF);
        when(builder.build()).thenReturn(mock(PermissionNode.class));
        NodeBuilderRegistry registry = mock(NodeBuilderRegistry.class);
        when(registry.forPermission()).thenReturn(builder);
        when(luckPerms.getNodeBuilderRegistry()).thenReturn(registry);
        provider = Mockito.mockStatic(LuckPermsProvider.class);
        provider.when(LuckPermsProvider::get).thenReturn(luckPerms);

        User user = mock(User.class);
        when(user.data()).thenReturn(data);
        UserManager users = mock(UserManager.class);
        when(users.getUser(ALEX)).thenReturn(user);
        when(users.saveUser(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(luckPerms.getUserManager()).thenReturn(users);
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    private static DataMutateResult result(boolean success) {
        DataMutateResult result = mock(DataMutateResult.class);
        when(result.wasSuccessful()).thenReturn(success);
        return result;
    }

    @Test
    @DisplayName("granting a node the player already had from an admin does not make it Core's to take")
    void alreadyHeldIsNotOurs() {
        DataMutateResult addFalse = result(false);
        when(data.add(any(Node.class))).thenReturn(addFalse);
        DataMutateResult removeTrue = result(true);
        when(data.remove(any(Node.class))).thenReturn(removeTrue);
        LuckPermsGrantStore store = new LuckPermsGrantStore(folder, luckPerms);

        store.grant(ALEX, "claims.admin");
        store.revoke(ALEX, "claims.admin");

        verify(data, never()).remove(any(Node.class));
    }

    @Test
    @DisplayName("revoking a node Core never granted leaves it alone")
    void revokeOnlyWhatWeGave() {
        DataMutateResult removeTrue = result(true);
        when(data.remove(any(Node.class))).thenReturn(removeTrue);
        LuckPermsGrantStore store = new LuckPermsGrantStore(folder, luckPerms);

        assertThat(store.revoke(ALEX, "worldedit.*")).isFalse();
        verify(data, never()).remove(any(Node.class));
    }

    @Test
    @DisplayName("what Core did grant, it can take away again")
    void ourOwnComesBack() {
        DataMutateResult addTrue = result(true);
        when(data.add(any(Node.class))).thenReturn(addTrue);
        DataMutateResult removeTrue = result(true);
        when(data.remove(any(Node.class))).thenReturn(removeTrue);
        LuckPermsGrantStore store = new LuckPermsGrantStore(folder, luckPerms);

        assertThat(store.grant(ALEX, "claims.admin")).isTrue();
        assertThat(store.revoke(ALEX, "claims.admin")).isTrue();
        verify(data).remove(any(Node.class));
    }
}
