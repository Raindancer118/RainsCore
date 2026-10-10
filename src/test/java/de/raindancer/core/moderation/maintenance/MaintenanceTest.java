package de.raindancer.core.moderation.maintenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MaintenanceTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(5_000L);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private Maintenance maintenance() {
        Maintenance maintenance = new Maintenance(folder.resolve("maintenance.yml"), clock::get);
        maintenance.load();
        return maintenance;
    }

    @Test
    @DisplayName("off, everybody may join")
    void offLetsEverybodyIn() {
        Maintenance maintenance = maintenance();

        assertThat(maintenance.isOn()).isFalse();
        assertThat(maintenance.mayJoin(alice, false)).isTrue();
    }

    @Test
    @DisplayName("on, only operators and the people on its own list may join")
    void onLetsOnlyOpsAndListIn() {
        Maintenance maintenance = maintenance();
        maintenance.turnOn("New spawn");
        maintenance.allow(bob, "Bob");

        assertThat(maintenance.mayJoin(alice, false)).isFalse();
        assertThat(maintenance.mayJoin(alice, true)).isTrue();
        assertThat(maintenance.mayJoin(bob, false)).isTrue();
        assertThat(maintenance.reason()).isEqualTo("New spawn");
        assertThat(maintenance.since()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("the list is kept while it is off, so the testers are still on it next time")
    void listOutlivesOff() {
        Maintenance maintenance = maintenance();
        maintenance.allow(bob, "Bob");
        maintenance.turnOn("");
        maintenance.turnOff();
        maintenance.turnOn("");

        assertThat(maintenance.mayJoin(bob, false)).isTrue();
        assertThat(maintenance.reason()).isEmpty();
    }

    @Test
    @DisplayName("taking somebody off says whether they were on it")
    void disallow() {
        Maintenance maintenance = maintenance();
        maintenance.allow(bob, "Bob");
        maintenance.turnOn("");

        assertThat(maintenance.disallow(bob)).isTrue();
        assertThat(maintenance.disallow(bob)).isFalse();
        assertThat(maintenance.mayJoin(bob, false)).isFalse();
    }

    @Test
    @DisplayName("switched on, the list and the reason survive a restart — a crash must not open the doors")
    void survivesRestart() {
        Maintenance maintenance = maintenance();
        maintenance.allow(bob, "Bob");
        maintenance.turnOn("Updating");

        Maintenance restarted = maintenance();
        assertThat(restarted.isOn()).isTrue();
        assertThat(restarted.reason()).isEqualTo("Updating");
        assertThat(restarted.allowed()).containsEntry(bob, "Bob");
        assertThat(restarted.mayJoin(alice, false)).isFalse();
    }

    @Test
    @DisplayName("a file that cannot be read keeps the server closed rather than opening it")
    void brokenFileStaysClosed() throws IOException {
        Maintenance maintenance = maintenance();
        maintenance.turnOn("Updating");
        Files.writeString(folder.resolve("maintenance.yml"), "on: [unclosed\n  - :");

        Maintenance restarted = maintenance();
        assertThat(restarted.problems()).isNotEmpty();
        assertThat(restarted.isOn()).isTrue();
    }

    @Test
    @DisplayName("switched on with a grace period: closed to joins at once, the kick due when it runs out")
    void countdown() {
        Maintenance maintenance = maintenance();
        maintenance.turnOn("Updating", 20_000);

        assertThat(maintenance.mayJoin(alice, false)).as("no new joins during the countdown").isFalse();
        assertThat(maintenance.secondsLeft()).isEqualTo(20);
        assertThat(maintenance.kickDue()).isFalse();

        clock.addAndGet(19_001);
        assertThat(maintenance.secondsLeft()).isEqualTo(1);
        clock.addAndGet(999);
        assertThat(maintenance.kickDue()).isTrue();
        assertThat(maintenance.secondsLeft()).isZero();
    }

    @Test
    @DisplayName("switched off during the countdown, nobody is kicked")
    void countdownCancelled() {
        Maintenance maintenance = maintenance();
        maintenance.turnOn("", 20_000);
        maintenance.turnOff();
        clock.addAndGet(30_000);

        assertThat(maintenance.kickDue()).isFalse();
    }

    @Test
    @DisplayName("switching it on again while it counts down does not push the kick back")
    void countdownNotRestarted() {
        Maintenance maintenance = maintenance();
        maintenance.turnOn("", 20_000);
        clock.addAndGet(15_000);
        maintenance.turnOn("Still updating", 20_000);

        assertThat(maintenance.secondsLeft()).isEqualTo(5);
        assertThat(maintenance.reason()).isEqualTo("Still updating");
    }

    @Test
    @DisplayName("an expected end is kept with the reason and survives a restart")
    void backAt() {
        Maintenance maintenance = maintenance();
        maintenance.turnOn("update", 20_000, 3 * 60_000);

        assertThat(maintenance.backAt()).isEqualTo(5_000L + 3 * 60_000);
        assertThat(maintenance().backAt()).isEqualTo(5_000L + 3 * 60_000);
        maintenance.turnOff();
        assertThat(maintenance.backAt()).isZero();
    }

    @Test
    @DisplayName("an update with a restart in between is measured from switching on to switching off, and the file keeps it")
    void updateMeasured() {
        Maintenance maintenance = maintenance();
        maintenance.turnOn("update", 60_000, 3 * 60_000);
        clock.addAndGet(90_000);

        Maintenance restarted = maintenance();
        clock.addAndGet(60_000);
        restarted.turnOff();

        assertThat(maintenance().updateTook()).containsExactly(150_000L);
        assertThat(maintenance().expectedUpdateMillis(3 * 60_000)).isEqualTo(150_000L);
    }

    @Test
    @DisplayName("an update called off before any restart, or other maintenance, says nothing about how long a restart takes")
    void notMeasured() {
        Maintenance maintenance = maintenance();
        maintenance.turnOn("update", 60_000, 3 * 60_000);
        clock.addAndGet(10_000);
        maintenance.turnOff();
        maintenance.turnOn("New spawn");
        clock.addAndGet(60_000);
        maintenance().turnOff();

        assertThat(maintenance().updateTook()).isEmpty();
        assertThat(maintenance().expectedUpdateMillis(3 * 60_000)).isEqualTo(3 * 60_000L);
    }

    @Test
    @DisplayName("the guess is the median of the last updates, so one forgotten /maintenance off does not skew it")
    void median() {
        for (long took : new long[]{100_000, 120_000, 5 * 60 * 60_000, 110_000}) {
            maintenance().turnOn("update", 60_000, 0);
            clock.addAndGet(took);
            maintenance().turnOff();
        }

        assertThat(maintenance().updateTook()).as("over four hours is no update, it is forgotten")
                .containsExactly(100_000L, 120_000L, 110_000L);
        assertThat(maintenance().expectedUpdateMillis(3 * 60_000)).isEqualTo(110_000L);
    }

    @Test
    @DisplayName("only the last ten updates are kept, so a server that got faster is soon guessed right")
    void keepsLastTen() {
        for (int i = 1; i <= 12; i++) {
            maintenance().turnOn("update", 60_000, 0);
            clock.addAndGet(i * 60_000L);
            maintenance().turnOff();
        }

        assertThat(maintenance().updateTook()).hasSize(10).startsWith(3 * 60_000L).endsWith(12 * 60_000L);
    }
}
