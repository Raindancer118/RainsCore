package de.raindancer.core.platform.command;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who somebody means when they type a name or a selector at a command.
 */
class PlayerTargetsTest {

    private final Server server = mock(Server.class);
    private final CommandSender sender = mock(CommandSender.class);

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    @Test
    @DisplayName("a selector goes to the server's own selector parser, and only players come back")
    void selectorsArePassedThrough() {
        Player alex = player("Alex");
        Player sam = player("Sam");
        List<Entity> matched = List.of(alex, mock(Pig.class), sam);
        doReturn(matched).when(server).selectEntities(sender, "@e[distance=..5]");

        assertThat(PlayerTargets.resolve(server, sender, "@e[distance=..5]")).containsExactly(alex, sam);
    }

    @Test
    @DisplayName("@a is every player the server's parser says it is")
    void everybody() {
        Player alex = player("Alex");
        doReturn(List.<Entity>of(alex)).when(server).selectEntities(sender, "@a");

        assertThat(PlayerTargets.resolve(server, sender, "@a")).containsExactly(alex);
    }

    @Test
    @DisplayName("a malformed selector matches nobody instead of throwing into the command")
    void aBrokenSelector() {
        when(server.selectEntities(any(), anyString())).thenThrow(new IllegalArgumentException("bad"));

        assertThat(PlayerTargets.resolve(server, sender, "@a[")).isEmpty();
    }

    @Test
    @DisplayName("a plain name is that online player, exactly, and nobody else")
    void aName() {
        Player alex = player("Alex");
        when(server.getPlayerExact("alex")).thenReturn(alex);

        assertThat(PlayerTargets.resolve(server, sender, "alex")).containsExactly(alex);
        assertThat(PlayerTargets.resolve(server, sender, "nobody")).isEmpty();
        verify(server, never()).selectEntities(any(), anyString());
    }

    @Test
    @DisplayName("nothing typed is nobody")
    void blank() {
        assertThat(PlayerTargets.resolve(server, sender, null)).isEmpty();
        assertThat(PlayerTargets.resolve(server, sender, " ")).isEmpty();
    }

    @Test
    @DisplayName("the same player matched twice by a selector is moved once")
    void noDuplicates() {
        Player alex = player("Alex");
        doReturn(List.<Entity>of(alex, alex)).when(server).selectEntities(sender, "@a");

        assertThat(PlayerTargets.resolve(server, sender, "@a")).containsExactly(alex);
    }

    @Test
    @DisplayName("suggestions: the selectors and every online name, by what has been typed so far")
    void suggestions() {
        Player alex = player("Alex");
        Player sam = player("Sam");
        doReturn(List.of(alex, sam)).when(server).getOnlinePlayers();

        assertThat(PlayerTargets.suggest(server, "")).containsExactly("@a", "@p", "@r", "@s", "Alex", "Sam");
        assertThat(PlayerTargets.suggest(server, "@")).containsExactly("@a", "@p", "@r", "@s");
        assertThat(PlayerTargets.suggest(server, "a")).containsExactly("Alex");
    }
}
