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
}
