package de.raindancer.core.ui.changelog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ChangelogTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private Changelog changelog() {
        Changelog changelog = new Changelog(folder.resolve("changelog.yml"), folder.resolve("changelog-seen.yml"), clock::get);
        changelog.reload();
        return changelog;
    }

    private void file(String yaml) throws IOException {
        Files.writeString(folder.resolve("changelog.yml"), yaml);
    }

    private static final String TWO_DRAFTS = """
            # Written by hand; /changelog publish <id> puts the time in.
            entries:
              leaderboard:
                title: Leaderboard fixed
                lines:
                  - Bedrock players are on /baltop again.
              shop:
                title: Shop
                lines:
                  - Cheaper bread.
                  - Dearer cake.
            """;

    @Test
    @DisplayName("an entry is a draft until it is published, and a draft is never shown to a player")
    void draftsStayHidden() throws IOException {
        file(TWO_DRAFTS);
        Changelog changelog = changelog();

        assertThat(changelog.drafts()).extracting(ChangelogEntry::id).containsExactly("leaderboard", "shop");
        assertThat(changelog.published()).isEmpty();
        assertThat(changelog.joined(alice, true)).isEmpty();
    }

    @Test
    @DisplayName("a returning player sees what was published since, once")
    void returningPlayerSeesItOnce() throws IOException {
        file(TWO_DRAFTS);
        Changelog changelog = changelog();
        changelog.joined(alice, true);
        clock.addAndGet(1000);

        assertThat(changelog.publish("leaderboard")).isEqualTo(Changelog.Publish.PUBLISHED);
        clock.addAndGet(1000);

        assertThat(changelog.joined(alice, true)).extracting(ChangelogEntry::id).containsExactly("leaderboard");
        assertThat(changelog.joined(alice, true)).isEmpty();
    }

    @Test
    @DisplayName("somebody new is not greeted with the history of a server they never saw")
    void newcomerSeesNothing() throws IOException {
        file(TWO_DRAFTS);
        Changelog changelog = changelog();
        changelog.publish("leaderboard");
        clock.addAndGet(1000);

        assertThat(changelog.joined(bob, false)).isEmpty();
        clock.addAndGet(1000);
        changelog.publish("shop");
        clock.addAndGet(1000);
        assertThat(changelog.joined(bob, true)).extracting(ChangelogEntry::id).containsExactly("shop");
    }

    @Test
    @DisplayName("a player the changelog has never seen but the server has gets what is published")
    void knownToServerButNotToChangelog() throws IOException {
        file(TWO_DRAFTS);
        Changelog changelog = changelog();
        changelog.publish("leaderboard");
        clock.addAndGet(1000);
        changelog.publish("shop");
        clock.addAndGet(1000);

        assertThat(changelog.joined(alice, true)).extracting(ChangelogEntry::id).containsExactly("shop", "leaderboard");
    }

    @Test
    @DisplayName("somebody away for months gets the newest few, not a wall of text")
    void capped() throws IOException {
        StringBuilder yaml = new StringBuilder("entries:\n");
        for (int i = 1; i <= 6; i++) {
            yaml.append("  e").append(i).append(":\n    title: Update ").append(i).append("\n    lines: [x]\n");
        }
        file(yaml.toString());
        Changelog changelog = changelog();
        changelog.joined(alice, true);
        for (int i = 1; i <= 6; i++) {
            clock.addAndGet(1000);
            changelog.publish("e" + i);
        }
        clock.addAndGet(1000);

        assertThat(changelog.joined(alice, true)).extracting(ChangelogEntry::id)
                .containsExactly("e6", "e5", "e4");
    }

    @Test
    @DisplayName("players online when it is published are counted as having seen it")
    void markedSeenWhenToldLive() throws IOException {
        file(TWO_DRAFTS);
        Changelog changelog = changelog();
        changelog.joined(alice, true);
        clock.addAndGet(1000);
        changelog.publish("leaderboard");
        changelog.seen(alice);
        clock.addAndGet(1000);

        assertThat(changelog.joined(alice, true)).isEmpty();
    }

    @Test
    @DisplayName("publishing writes the time into the file and keeps the rest of it; it survives a restart")
    void publishingIsKept() throws IOException {
        file(TWO_DRAFTS);
        Changelog changelog = changelog();
        changelog.joined(alice, true);
        clock.addAndGet(1000);
        changelog.publish("leaderboard");
        changelog.save();

        String written = Files.readString(folder.resolve("changelog.yml"));
        assertThat(written).contains("Bedrock players are on /baltop again.").contains("Dearer cake.");

        clock.addAndGet(1000);
        Changelog restarted = changelog();
        assertThat(restarted.published()).extracting(ChangelogEntry::id).containsExactly("leaderboard");
        assertThat(restarted.entry("leaderboard")).hasValueSatisfying(entry ->
                assertThat(entry.publishedAt()).isEqualTo(1_001_000L));
        assertThat(restarted.joined(alice, true)).extracting(ChangelogEntry::id).containsExactly("leaderboard");
    }

    @Test
    @DisplayName("publish says why it did nothing: no such entry, or already out")
    void publishRefusals() throws IOException {
        file(TWO_DRAFTS);
        Changelog changelog = changelog();

        assertThat(changelog.publish("nope")).isEqualTo(Changelog.Publish.UNKNOWN);
        assertThat(changelog.publish("shop")).isEqualTo(Changelog.Publish.PUBLISHED);
        assertThat(changelog.publish("shop")).isEqualTo(Changelog.Publish.ALREADY);
    }

    @Test
    @DisplayName("an entry without a title or without lines is reported, not shown")
    void brokenEntries() throws IOException {
        file("""
                entries:
                  empty:
                    title: Nothing in it
                  untitled:
                    lines: [something]
                  fine:
                    title: Fine
                    lines: [ok]
                """);
        Changelog changelog = changelog();

        assertThat(changelog.drafts()).extracting(ChangelogEntry::id).containsExactly("fine");
        assertThat(changelog.problems()).hasSize(2);
    }

    @Test
    @DisplayName("no file yet is an empty changelog, and reload picks up what was written by hand")
    void noFileThenReload() throws IOException {
        Changelog changelog = changelog();
        assertThat(changelog.drafts()).isEmpty();
        assertThat(changelog.problems()).isEmpty();

        file(TWO_DRAFTS);
        changelog.reload();
        assertThat(changelog.drafts()).hasSize(2);
    }
}
