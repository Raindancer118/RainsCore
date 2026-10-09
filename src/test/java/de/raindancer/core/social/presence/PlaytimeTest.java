package de.raindancer.core.social.presence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlaytimeTest {

    @TempDir
    Path folder;

    private final UUID alice = UUID.randomUUID();

    private Playtime playtime() {
        Playtime playtime = new Playtime(folder.resolve("playtime.yml"));
        playtime.load();
        return playtime;
    }

    @Test
    @DisplayName("every minute counts towards playtime; only the minutes not away count as active")
    void counting() {
        Playtime playtime = playtime();
        playtime.minute(alice, false);
        playtime.minute(alice, false);
        playtime.minute(alice, true);

        assertThat(playtime.minutes(alice)).isEqualTo(3);
        assertThat(playtime.activeMinutes(alice)).isEqualTo(2);
        assertThat(playtime.played(alice)).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    @DisplayName("somebody first counted starts from what the game already recorded, once")
    void seeded() {
        Playtime playtime = playtime();
        playtime.seed(alice, 20L * 60 * 90);   // 90 minutes in ticks
        playtime.seed(alice, 20L * 60 * 500);  // ignored: already known
        playtime.minute(alice, false);

        assertThat(playtime.minutes(alice)).isEqualTo(91);
        assertThat(playtime.activeMinutes(alice)).isEqualTo(91);
    }

    @Test
    @DisplayName("it survives a restart; nobody counted yet has played nothing")
    void persisted() throws Exception {
        Playtime playtime = playtime();
        playtime.minute(alice, false);
        playtime.minute(alice, true);
        assertThat(playtime.isDirty()).isTrue();
        assertThat(playtime.save()).isTrue();
        assertThat(playtime.isDirty()).isFalse();
        assertThat(Files.exists(folder.resolve("playtime.yml"))).isTrue();

        Playtime again = playtime();
        assertThat(again.minutes(alice)).isEqualTo(2);
        assertThat(again.activeMinutes(alice)).isEqualTo(1);
        assertThat(again.minutes(UUID.randomUUID())).isZero();
        assertThat(again.isKnown(UUID.randomUUID())).isFalse();
    }
}
