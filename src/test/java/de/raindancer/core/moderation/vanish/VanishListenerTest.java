package de.raindancer.core.moderation.vanish;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The moments vanish would otherwise announce itself without meaning to — see the class's own note.
 */
class VanishListenerTest {

    private static final UUID MOD = UUID.randomUUID();

    private static Vanish vanishHiding(UUID who) {
        Vanish vanish = new Vanish(mock(VanishSink.class));
        vanish.vanish(who);
        return vanish;
    }

    private static Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Nested
    @DisplayName("an advancement completed")
    class Advancements {

        @Test
        @DisplayName("a vanished player's own broadcast is silenced")
        void silencesTheVanishedPlayer() {
            Vanish vanish = vanishHiding(MOD);
            VanishListener listener = new VanishListener(mock(Plugin.class), vanish, (String) null);
            Player player = playerWithId(MOD);
            PlayerAdvancementDoneEvent event = mock(PlayerAdvancementDoneEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onAdvancement(event);

            verify(event).message(null);
        }

        @Test
        @DisplayName("an ordinary player's broadcast is left exactly as it was")
        void leavesAnOrdinaryPlayerAlone() {
            Vanish vanish = new Vanish(mock(VanishSink.class));
            VanishListener listener = new VanishListener(mock(Plugin.class), vanish, (String) null);
            Player player = playerWithId(MOD);
            PlayerAdvancementDoneEvent event = mock(PlayerAdvancementDoneEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onAdvancement(event);

            verify(event, never()).message(org.mockito.ArgumentMatchers.any());
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("somebody revealed while offline is shown to everybody when they come back")
    void revealedOfflineIsShownOnReturn() {
        Plugin plugin = mock(Plugin.class);
        Vanish vanish = vanishHiding(MOD);
        vanish.reveal(MOD);          // while they were logged out: nobody could be shown them then
        Player joining = playerWithId(MOD);
        Player viewer = playerWithId(UUID.randomUUID());
        when(joining.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        org.bukkit.event.player.PlayerJoinEvent event =
                new org.bukkit.event.player.PlayerJoinEvent(joining, (net.kyori.adventure.text.Component) null);

        try (org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenAnswer(call -> java.util.List.of(joining, viewer));
            new VanishListener(plugin, vanish, "rainscore.vanish.see").onJoin(event);
        }

        // Each viewer still online kept the hide this plugin put on them; only a show takes it off.
        verify(viewer).showPlayer(plugin, joining);
    }
    @Nested
    @DisplayName("staff and a vanished colleague")
    class StaffSeeStaff {

        private final Plugin plugin = mock(Plugin.class);

        private Player staff(UUID id) {
            Player player = playerWithId(id);
            when(player.getName()).thenReturn("Staff");
            return player;
        }

        @Test
        @DisplayName("somebody who may vanish sees vanished staff when staff-see-staff is on, and not when off")
        void sightFollowsTheSetting() {
            Vanish vanish = new Vanish(mock(VanishSink.class));
            vanish.countAsStaff("mod.vanish");
            Player mod = playerWithId(UUID.randomUUID());
            when(mod.hasPermission("mod.vanish")).thenReturn(true);
            Player player = playerWithId(UUID.randomUUID());

            vanish.staffSeeStaff(true);
            org.assertj.core.api.Assertions.assertThat(VanishSight.sees(mod, vanish, "rainscore.vanish.see")).isTrue();
            org.assertj.core.api.Assertions.assertThat(VanishSight.sees(player, vanish, "rainscore.vanish.see")).isFalse();

            vanish.staffSeeStaff(false);
            org.assertj.core.api.Assertions.assertThat(VanishSight.sees(mod, vanish, "rainscore.vanish.see")).isFalse();
            when(mod.hasPermission("rainscore.vanish.see")).thenReturn(true);
            org.assertj.core.api.Assertions.assertThat(VanishSight.sees(mod, vanish, "rainscore.vanish.see")).isTrue();
        }

        @Test
        @DisplayName("a vanished player's arrival is hidden from players but told to staff who can see them")
        void arrivalToldToStaff() {
            Vanish vanish = vanishHiding(MOD);
            Player joining = staff(MOD);
            Player colleague = playerWithId(UUID.randomUUID());
            Player bystander = playerWithId(UUID.randomUUID());
            vanish.maySeeVanished(colleague.getUniqueId(), true);
            org.bukkit.event.player.PlayerJoinEvent event = new org.bukkit.event.player.PlayerJoinEvent(joining,
                    net.kyori.adventure.text.Component.text("Staff joined the game"));

            try (org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
                bukkit.when(org.bukkit.Bukkit::getOnlinePlayers)
                        .thenAnswer(call -> java.util.List.of(joining, colleague, bystander));
                new VanishListener(plugin, vanish, who -> false).onJoin(event);
            }

            org.assertj.core.api.Assertions.assertThat(event.joinMessage()).isNull();
            org.mockito.ArgumentCaptor<net.kyori.adventure.text.Component> told =
                    org.mockito.ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
            verify(colleague).sendMessage(told.capture());
            org.assertj.core.api.Assertions.assertThat(plain(told.getValue()))
                    .contains("vanished").contains("Staff joined the game");
            verify(bystander, never()).sendMessage(org.mockito.ArgumentMatchers.any(net.kyori.adventure.text.Component.class));
        }

        @Test
        @DisplayName("a vanished player's departure is told to staff who can see them, to nobody else")
        void departureToldToStaff() {
            Vanish vanish = vanishHiding(MOD);
            Player leaving = staff(MOD);
            Player colleague = playerWithId(UUID.randomUUID());
            Player bystander = playerWithId(UUID.randomUUID());
            vanish.maySeeVanished(colleague.getUniqueId(), true);
            org.bukkit.event.player.PlayerQuitEvent event = new org.bukkit.event.player.PlayerQuitEvent(leaving,
                    net.kyori.adventure.text.Component.text("Staff left the game"),
                    org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED);

            try (org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
                bukkit.when(org.bukkit.Bukkit::getOnlinePlayers)
                        .thenAnswer(call -> java.util.List.of(leaving, colleague, bystander));
                new VanishListener(plugin, vanish, who -> false).onQuit(event);
            }

            org.assertj.core.api.Assertions.assertThat(event.quitMessage()).isNull();
            org.mockito.ArgumentCaptor<net.kyori.adventure.text.Component> told =
                    org.mockito.ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
            verify(colleague).sendMessage(told.capture());
            org.assertj.core.api.Assertions.assertThat(plain(told.getValue()))
                    .contains("vanished").contains("Staff left the game");
            verify(bystander, never()).sendMessage(org.mockito.ArgumentMatchers.any(net.kyori.adventure.text.Component.class));
        }

        @Test
        @DisplayName("staff joining are allowed to see who is already vanished")
        void joiningStaffSeeVanished() {
            Vanish vanish = vanishHiding(MOD);
            Player hidden = staff(MOD);
            Player joining = playerWithId(UUID.randomUUID());
            org.bukkit.event.player.PlayerJoinEvent event =
                    new org.bukkit.event.player.PlayerJoinEvent(joining, (net.kyori.adventure.text.Component) null);

            try (org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
                bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenAnswer(call -> java.util.List.of(hidden, joining));
                new VanishListener(plugin, vanish, who -> who == joining).onJoin(event);
            }

            org.assertj.core.api.Assertions.assertThat(vanish.maySeeVanished(joining.getUniqueId())).isTrue();
            verify(joining, never()).hidePlayer(plugin, hidden);
        }

        private String plain(net.kyori.adventure.text.Component component) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(component);
        }
    }
}
