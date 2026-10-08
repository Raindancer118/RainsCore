package de.raindancer.core.ui.menu;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeadSkinsTest {

    private static final UUID JAVA = UUID.fromString("b7628f08-1311-4e9b-abe3-27b5b2650c2c");
    private static final UUID BEDROCK = UUID.fromString("00000000-0000-0000-0009-01fbceab8aa5");

    private static PlayerProfile profile(boolean textured) {
        PlayerProfile profile = mock(PlayerProfile.class);
        when(profile.hasTextures()).thenReturn(textured);
        when(profile.clone()).thenReturn(profile);
        return profile;
    }

    private static OfflinePlayer online(UUID id, PlayerProfile profile) {
        OfflinePlayer offline = mock(OfflinePlayer.class);
        Player player = mock(Player.class);
        when(offline.getUniqueId()).thenReturn(id);
        when(offline.getPlayer()).thenReturn(player);
        when(player.getPlayerProfile()).thenReturn(profile);
        return offline;
    }

    private static OfflinePlayer offline(UUID id) {
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getUniqueId()).thenReturn(id);
        return offline;
    }

    @Test
    @DisplayName("an online player's head uses the skin the server already has — no trip to Mojang")
    void onlineUsesOwnProfile() {
        HeadSkins skins = new HeadSkins();
        PlayerProfile textured = profile(true);
        HeadSkins.Skin skin = skins.of(online(JAVA, textured));
        assertThat(skin.profile()).isSameAs(textured);
        assertThat(skin.lookUp()).isFalse();
    }

    @Test
    @DisplayName("once seen, a player's skin is remembered for when they are offline")
    void remembersAfterLogout() {
        HeadSkins skins = new HeadSkins();
        PlayerProfile textured = profile(true);
        skins.of(online(JAVA, textured));
        HeadSkins.Skin later = skins.of(offline(JAVA));
        assertThat(later.profile()).isSameAs(textured);
        assertThat(later.lookUp()).isFalse();
    }

    @Test
    @DisplayName("a Bedrock player Mojang cannot know gets a plain head instead of a failing lookup")
    void bedrockNeverLooksUp() {
        HeadSkins skins = new HeadSkins();
        HeadSkins.Skin skin = skins.of(online(BEDROCK, profile(false)));
        assertThat(skin.profile()).isNull();
        assertThat(skin.lookUp()).isFalse();
        assertThat(skins.of(offline(BEDROCK)).lookUp()).isFalse();
    }

    @Test
    @DisplayName("a Java player never seen with a skin is looked up, as before")
    void unknownJavaLooksUp() {
        HeadSkins skins = new HeadSkins();
        assertThat(skins.of(offline(JAVA)).lookUp()).isTrue();
        assertThat(skins.of(online(JAVA, profile(false))).lookUp()).isTrue();
    }

    @Test
    @DisplayName("the memory stays bounded however many players pass through")
    void bounded() {
        HeadSkins skins = new HeadSkins();
        for (int i = 0; i < 5000; i++) {
            skins.of(online(new UUID(1, i), profile(true)));
        }
        assertThat(skins.size()).isLessThanOrEqualTo(HeadSkins.CAPACITY);
    }
}
