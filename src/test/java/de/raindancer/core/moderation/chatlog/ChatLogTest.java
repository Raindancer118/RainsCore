package de.raindancer.core.moderation.chatlog;

import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ChatLogTest {

    @TempDir
    java.nio.file.Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final UUID bo = UUID.randomUUID();
    private final UUID cy = UUID.randomUUID();
    private Database database;
    private ChatLog log;

    @BeforeEach
    void open() {
        database = Database.open(folder.resolve("chatlog.db"), ChatLog.SCHEMA, () -> false);
        log = new ChatLog(database, clock::get);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("everything somebody said is kept, newest first, and only theirs")
    void perPlayer() {
        log.record(bo, "Bo", ChatLog.PUBLIC, "hello there");
        clock.addAndGet(1000);
        log.record(cy, "Cy", ChatLog.PUBLIC, "hi bo");
        clock.addAndGet(1000);
        log.record(bo, "Bo", "staff", "second line");

        List<ChatLog.Line> said = log.of(bo, "", 50, 0);
        assertThat(said).extracting(ChatLog.Line::text).containsExactly("second line", "hello there");
        assertThat(said.getFirst().channel()).isEqualTo("staff");
        assertThat(log.of(cy, "", 50, 0)).hasSize(1);
    }

    @Test
    @DisplayName("searching finds lines holding every word, in any case, and takes % and _ literally")
    void search() {
        log.record(bo, "Bo", ChatLog.PUBLIC, "I found DIAMONDS at the base");
        log.record(bo, "Bo", ChatLog.PUBLIC, "diamonds everywhere");
        log.record(bo, "Bo", ChatLog.PUBLIC, "100% sure");
        assertThat(log.of(bo, "diamonds base", 50, 0)).extracting(ChatLog.Line::text)
                .containsExactly("I found DIAMONDS at the base");
        assertThat(log.of(bo, "100%", 50, 0)).hasSize(1);
        assertThat(log.of(bo, "_", 50, 0)).isEmpty();
        assertThat(log.count(bo)).isEqualTo(3);
    }

    @Test
    @DisplayName("pages go back through the history")
    void paging() {
        for (int i = 0; i < 30; i++) {
            log.record(bo, "Bo", ChatLog.PUBLIC, "line " + i);
            clock.incrementAndGet();
        }
        assertThat(log.of(bo, "", 10, 0).getFirst().text()).isEqualTo("line 29");
        assertThat(log.of(bo, "", 10, 20).getLast().text()).isEqualTo("line 0");
    }

    @Test
    @DisplayName("lines older than the retention are deleted")
    void retention() {
        log.record(bo, "Bo", ChatLog.PUBLIC, "old");
        clock.addAndGet(Duration.ofDays(400).toMillis());
        log.record(bo, "Bo", ChatLog.PUBLIC, "new");
        assertThat(log.forgetOlderThan(Duration.ofDays(365))).isEqualTo(1);
        assertThat(log.of(bo, "", 50, 0)).extracting(ChatLog.Line::text).containsExactly("new");
    }

    @Test
    @DisplayName("everything recorded is handed to whoever watches, as it is said")
    void watchers() {
        List<String> seen = new ArrayList<>();
        java.util.function.Consumer<ChatLog.Line> watcher = line -> seen.add(line.name() + ": " + line.text());
        ChatLog.watch(watcher);
        try {
            log.record(bo, "Bo", ChatLog.PUBLIC, "watched");
        } finally {
            ChatLog.unwatch(watcher);
        }
        log.record(bo, "Bo", ChatLog.PUBLIC, "not watched");
        assertThat(seen).containsExactly("Bo: watched");
    }

    @Test
    @DisplayName("switched off, nothing is kept and nothing is watched")
    void switchedOff() {
        log.enabled(false);
        log.record(bo, "Bo", ChatLog.PUBLIC, "nothing");
        assertThat(log.of(bo, "", 50, 0)).isEmpty();
    }
}
