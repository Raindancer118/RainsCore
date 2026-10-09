package de.raindancer.core.moderation.invsee;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An owner logging in wins over a moderator editing their offline file. Since 26.3 that is heard on
 * {@code PlayerConnectionValidateLoginEvent} (PlayerLoginEvent is deprecated): fired at login and
 * again when configuration ends, just before the server reads the file.
 */
class InvseeListenerLoginTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final Inventories inventories = mock(Inventories.class);
    private final InvseeListener listener = new InvseeListener(inventories);

    private static PlayerProfile profile(UUID id) {
        PlayerProfile profile = mock(PlayerProfile.class);
        when(profile.getId()).thenReturn(id);
        return profile;
    }

    @Test
    @DisplayName("at the end of configuration — right before the file is read — the owner's edit is stopped")
    void configuration() {
        PlayerProfile owner = profile(OWNER);
        PlayerConfigurationConnection connection = mock(PlayerConfigurationConnection.class);
        when(connection.getProfile()).thenReturn(owner);

        listener.onLogin(new PlayerConnectionValidateLoginEvent(connection, null));

        verify(inventories).somebodyJoined(OWNER);
    }

    @Test
    @DisplayName("at login, once the profile is authenticated")
    void login() {
        PlayerProfile owner = profile(OWNER);
        PlayerLoginConnection connection = mock(PlayerLoginConnection.class);
        when(connection.getAuthenticatedProfile()).thenReturn(owner);

        listener.onLogin(new PlayerConnectionValidateLoginEvent(connection, null));

        verify(inventories).somebodyJoined(OWNER);
    }

    @Test
    @DisplayName("a connection with no known player yet is left alone")
    void nobodyYet() {
        PlayerLoginConnection connection = mock(PlayerLoginConnection.class);

        listener.onLogin(new PlayerConnectionValidateLoginEvent(connection, null));

        verify(inventories, never()).somebodyJoined(any());
    }
}
