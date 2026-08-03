package de.raindancer.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rules about which thread work is allowed to happen on, enforced at build time.
 *
 * <h2>Why these are read out of the source</h2>
 * Because the failure they prevent cannot be reproduced in a unit test and is invisible in a normal
 * one. On Folia there is no single main thread: each region ticks on its own, a player is owned by
 * the region they are standing in, and touching them from anywhere else throws
 * {@code IllegalStateException} — sometimes. On Paper the same code is merely a stall. So the bug
 * ships, looks fine on the developer's Paper server, and takes a region down on the server that
 * actually has players on it.
 *
 * <p>The two things that go wrong are always the same two:
 * <ul>
 *   <li><b>Touching a player from the wrong thread.</b> Closing somebody's window, setting a slot in
 *       their inventory — done from wherever the reason happened to arrive, which for a login is the
 *       connection thread and for a quit is somebody else's region.</li>
 *   <li><b>Doing file work on a server thread.</b> Reading and un-gzipping a player file takes long
 *       enough to be felt, and the thread it is taken from is the one running the world.</li>
 * </ul>
 *
 * <p>Neither is caught by a compiler or by any test that does not have a real server with real
 * regions. What <em>can</em> be checked cheaply, here, on every build, is that the calls which must
 * be scheduled are written as scheduled calls. It is a coarse instrument and it is far better than
 * the alternative, which is remembering.
 *
 * <p>The live half — that the scheduler really does put the work where it says — is checked on a
 * running server by {@code RainsCoreTestPlugin}. Neither half replaces the other.
 */
@DisplayName("work happens on the thread it is allowed to happen on")
class ThreadingRulesTest {

    private static final Path SOURCE = Path.of("src/main/java/de/raindancer/core");

    private static String read(String path) throws IOException {
        return Files.readString(SOURCE.resolve(path));
    }

    /** Source with block comments and line comments taken out, so prose cannot satisfy a rule. */
    private static String code(String path) throws IOException {
        return read(path)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private static List<Path> javaFilesIn(String packageName) throws IOException {
        try (Stream<Path> found = Files.walk(SOURCE.resolve(packageName))) {
            return found.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Nested
    @DisplayName("nothing touches a player from whichever thread it happened to be on")
    class PlayerAccess {

        @Test
        @DisplayName("a window is closed on the thread that owns the player, not the caller's")
        void windowsAreClosedOnTheOwningThread() throws IOException {
            String plugin = code("RainsCorePlugin.java");
            int at = plugin.indexOf("new InventoryViews(");
            assertThat(at).as("the callback this rule is about has moved or gone").isNotNegative();
            String callback = plugin.substring(at, plugin.indexOf("});", at));

            assertThat(callback)
                    .as("this callback is invoked from wherever the reason arrived — a login on the "
                            + "connection thread, a quit on another player's region — and calling "
                            + "closeInventory() straight from there is an IllegalStateException on "
                            + "Folia that takes the login event down with it")
                    .contains("Scheduling.entity(");
        }

        @Test
        @DisplayName("changes to a live inventory are pushed from the window, not from a listener")
        void liveChangesGoThroughTheSource() throws IOException {
            String listener = code("moderation/invsee/InvseeListener.java");

            assertThat(listener)
                    .as("a listener that reaches into a player's inventory itself is a listener "
                            + "running on the clicking moderator's thread, not the owner's")
                    .doesNotContain("getInventory().setItem(")
                    .doesNotContain("getEnderChest().setItem(");
        }
    }

    @Nested
    @DisplayName("no file work on a thread that is running the world")
    class FileWork {

        @Test
        @DisplayName("a saved inventory is read and written off the server's threads")
        void savedInventoriesAreReadOffThread() throws IOException {
            String inventories = code("moderation/invsee/Inventories.java");

            long fileCalls = inventories.lines()
                    .filter(line -> line.contains("saved.read(") || line.contains("saved.write("))
                    .count();
            assertThat(fileCalls)
                    .as("the calls this rule is about have gone; the rule needs rewriting rather "
                            + "than deleting")
                    .isGreaterThan(0);
            assertThat(inventories)
                    .as("reading a player file means opening it and un-gzipping it. Done on the "
                            + "thread a command arrived on, that is a stall on the thread running "
                            + "the world — and on Folia it is a stall on one region while the "
                            + "others tick past it")
                    .contains("Scheduling.async(");
        }

        @Test
        @DisplayName("the timer that writes the stores is an async one, not a global one")
        void theSaveTimerIsAsync() throws IOException {
            String plugin = code("RainsCorePlugin.java");
            int at = plugin.indexOf("places.flush()");
            assertThat(at).as("the save timer this rule is about has moved or gone").isNotNegative();
            // Backwards to whichever timer call this block belongs to.
            String before = plugin.substring(Math.max(0, at - 600), at);
            int lastTimer = before.lastIndexOf("Scheduling.");

            assertThat(before.substring(lastTimer))
                    .as("every one of those flushes writes a file or a database. On the global timer "
                            + "that is disk I/O on the thread that ticks the world — a freeze every "
                            + "two minutes, which is exactly how this shipped once already")
                    .startsWith("Scheduling.asyncTimer");
        }

        @Test
        @DisplayName("the audit journal is written off the server's threads")
        void theAuditTimerIsAsync() throws IOException {
            String plugin = code("RainsCorePlugin.java");
            int at = plugin.indexOf("audit.flush()");
            assertThat(at).isNotNegative();
            String before = plugin.substring(Math.max(0, at - 400), at);

            assertThat(before.substring(before.lastIndexOf("Scheduling.")))
                    .as("recording an audit entry is free precisely because writing it happens "
                            + "somewhere else")
                    .startsWith("Scheduling.asyncTimer");
        }

        @Test
        @DisplayName("a timer that writes to disk is stopped before the databases close")
        void writingTimersAreCancelled() throws IOException {
            String plugin = code("RainsCorePlugin.java");
            int closes = plugin.indexOf("databases.close()");
            assertThat(closes).as("the shutdown this rule is about has moved").isNotNegative();

            assertThat(plugin.substring(0, closes))
                    .as("an async timer firing while onDisable closes the databases writes into one "
                            + "that is going away, and loses what it was carrying — the one moment "
                            + "when a lost audit entry cannot be recovered")
                    .contains("stopTimer(savingTask)")
                    .contains("stopTimer(auditFlushTask)");
        }

        @Test
        @DisplayName("the NBT reader is never called straight out of an event handler")
        void nbtIsNotReadInListeners() throws IOException {
            for (Path file : javaFilesIn("moderation/invsee")) {
                String source = code("moderation/invsee/" + file.getFileName());
                if (!source.contains("implements Listener")) {
                    continue;
                }
                assertThat(source)
                        .as(file.getFileName() + " is an event handler, and event handlers run on "
                                + "the server's own threads")
                        .doesNotContain("Nbt.read(")
                        .doesNotContain("Nbt.write(");
            }
        }

        @Test
        @DisplayName("only the one class whose job it is touches player files at all")
        void onlyOneClassReadsPlayerFiles() throws IOException {
            List<String> offenders = new ArrayList<>();
            for (Path file : javaFilesIn("moderation/invsee")) {
                String name = file.getFileName().toString();
                if (name.equals("PlayerDataInventorySource.java")) {
                    continue;
                }
                String source = code("moderation/invsee/" + name);
                if (source.contains("Nbt.read(") || source.contains("Nbt.write(")) {
                    offenders.add(name);
                }
            }
            assertThat(offenders)
                    .as("one door to the disk, so there is one place that has to get the "
                            + "scheduling, the atomic replace and the backup right")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("nothing waits for another thread while holding up this one")
    class NoBlocking {

        @Test
        @DisplayName("no scheduled work is waited on from a server thread")
        void nothingJoinsOrGets() throws IOException {
            List<String> offenders = new ArrayList<>();
            for (Path file : javaFilesIn("moderation/invsee")) {
                String source = code("moderation/invsee/" + file.getFileName());
                if (source.contains(".join()") || source.contains("CountDownLatch")
                        || source.contains(".get(5,") || source.contains("Thread.sleep(")) {
                    offenders.add(file.getFileName().toString());
                }
            }
            assertThat(offenders)
                    .as("waiting on the main thread for work scheduled onto the main thread is a "
                            + "deadlock, and it is the one this project has already had once")
                    .isEmpty();
        }
    }
}
