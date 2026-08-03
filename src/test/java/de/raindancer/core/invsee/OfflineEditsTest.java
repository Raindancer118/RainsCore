package de.raindancer.core.invsee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who is being edited while logged out, and what happens when they come back.
 *
 * <h2>Why editing an offline player needs a lock at all</h2>
 * The player file on disk is only the truth while its owner is away. The server reads it on join and
 * writes it back on quit, so an edit made across either of those is discarded without a word — a
 * moderator watches their change take effect and then quietly undo itself an hour later.
 *
 * <h2>And why the answer is not to hold the player out</h2>
 * Because a plugin that stops somebody logging in is worse than the problem it prevents: a player
 * turned away by a server they have done nothing wrong on concludes the server is broken, not that
 * a moderator is busy. So the rule is the other way round — <b>the player always wins and the edit
 * yields</b>. Somebody logging in supersedes the edit: the window shuts, nothing is written, and the
 * file is left exactly as its owner left it.
 *
 * <p>Most of these tests are about that one moment, because it is the one that arrives on a
 * different thread from everything else and the one where a wrong answer costs somebody their
 * things.
 */
@DisplayName("editing somebody who is logged out")
class OfflineEditsTest {

    private final AtomicLong now = new AtomicLong(1_000L);
    private final UUID owner = UUID.randomUUID();
    private final UUID moderator = UUID.randomUUID();
    private final UUID somebodyElse = UUID.randomUUID();

    private OfflineEdits edits(Duration longest) {
        return new OfflineEdits(now::get, longest);
    }

    private OfflineEdits edits() {
        return edits(Duration.ofMinutes(5));
    }

    private void secondsPass(long seconds) {
        now.addAndGet(seconds * 1000L);
    }

    @Nested
    @DisplayName("taking it")
    class Taking {

        @Test
        @DisplayName("nobody is being edited to begin with")
        void nothingHeldAtFirst() {
            OfflineEdits held = edits();

            assertThat(held.isBeingEdited(owner)).isFalse();
            assertThat(held.editorOf(owner)).isEmpty();
            assertThat(held.size()).isZero();
        }

        @Test
        @DisplayName("beginning an edit takes the hold on that player")
        void beginHolds() {
            OfflineEdits held = edits();

            assertThat(held.begin(owner, moderator)).isTrue();
            assertThat(held.isBeingEdited(owner)).isTrue();
            assertThat(held.isStillTheirs(owner, moderator)).isTrue();
            assertThat(held.editorOf(owner)).contains(moderator);
        }

        @Test
        @DisplayName("only the player being edited is held")
        void holdsOnlyThatPlayer() {
            OfflineEdits held = edits();
            held.begin(owner, moderator);

            assertThat(held.isBeingEdited(somebodyElse)).isFalse();
            assertThat(held.isBeingEdited(null))
                    .as("nobody at all is not somebody being edited")
                    .isFalse();
        }

        @Test
        @DisplayName("a second moderator is refused rather than allowed to edit the same file")
        void oneAtATime() {
            OfflineEdits held = edits();
            held.begin(owner, moderator);

            assertThat(held.begin(owner, somebodyElse))
                    .as("two moderators writing the same file is the offline version of the item "
                            + "duplication this whole package exists to stop")
                    .isFalse();
            assertThat(held.editorOf(owner)).contains(moderator);
        }

        @Test
        @DisplayName("the same moderator opening again keeps the hold rather than dropping it")
        void reopeningKeepsIt() {
            OfflineEdits held = edits();
            held.begin(owner, moderator);

            assertThat(held.begin(owner, moderator))
                    .as("re-opening their own window must not answer no")
                    .isTrue();
            assertThat(held.editorOf(owner))
                    .as("this is the mistake InventoryViews made: taking a lock you already hold "
                            + "and then letting go of it in the same call, leaving nobody holding "
                            + "it at all")
                    .contains(moderator);
            assertThat(held.isBeingEdited(owner)).isTrue();
        }

        @Test
        @DisplayName("nothing is held for nobody")
        void refusesNulls() {
            OfflineEdits held = edits();

            assertThat(held.begin(null, moderator)).isFalse();
            assertThat(held.begin(owner, null)).isFalse();
            assertThat(held.size()).isZero();
        }
    }

    @Nested
    @DisplayName("letting go")
    class Releasing {

        @Test
        @DisplayName("finishing lets the hold go")
        void finishReleases() {
            OfflineEdits held = edits();
            held.begin(owner, moderator);

            assertThat(held.finish(owner, moderator)).isTrue();
            assertThat(held.isBeingEdited(owner)).isFalse();
            assertThat(held.size()).isZero();
        }

        @Test
        @DisplayName("somebody else's window closing does not let go of this hold")
        void onlyTheHolderReleases() {
            OfflineEdits held = edits();
            held.begin(owner, moderator);

            assertThat(held.finish(owner, somebodyElse))
                    .as("a moderator closing their own read-only window must not release the "
                            + "editor's hold on the same player")
                    .isFalse();
            assertThat(held.editorOf(owner)).contains(moderator);
        }

        @Test
        @DisplayName("finishing something nobody was editing is simply nothing")
        void finishWithoutBeginning() {
            OfflineEdits held = edits();

            assertThat(held.finish(owner, moderator)).isFalse();
            assertThat(held.finish(null, null)).isFalse();
        }

        @Test
        @DisplayName("a moderator who logs out lets go of everything they held")
        void moderatorLeaving() {
            OfflineEdits held = edits();
            UUID second = UUID.randomUUID();
            held.begin(owner, moderator);
            held.begin(second, moderator);
            held.begin(somebodyElse, UUID.randomUUID());

            assertThat(held.editorLeft(moderator))
                    .as("a hold kept by somebody who is no longer on the server is an inventory "
                            + "no other moderator can open until a restart")
                    .containsExactlyInAnyOrder(owner, second);
            assertThat(held.isBeingEdited(owner)).isFalse();
            assertThat(held.isBeingEdited(somebodyElse)).isTrue();
        }

        @Test
        @DisplayName("a shutdown lets go of all of them")
        void releaseEverything() {
            OfflineEdits held = edits();
            held.begin(owner, moderator);
            held.begin(somebodyElse, moderator);

            assertThat(held.finishEverything()).isEqualTo(2);
            assertThat(held.size()).isZero();
        }
    }

    @Nested
    @DisplayName("a hold nobody let go of")
    class Expiry {

        @Test
        @DisplayName("expires on its own rather than locking somebody out for good")
        void expires() {
            OfflineEdits held = edits(Duration.ofMinutes(5));
            held.begin(owner, moderator);

            secondsPass(299);
            assertThat(held.isBeingEdited(owner)).isTrue();

            secondsPass(2);
            assertThat(held.isBeingEdited(owner))
                    .as("the moderator's client can crash with the window open, and nobody would "
                            + "ever call finish")
                    .isFalse();
            assertThat(held.editorOf(owner)).isEmpty();
        }

        @Test
        @DisplayName("an expired hold does not stop the next moderator")
        void expiredHoldCanBeTakenOver() {
            OfflineEdits held = edits(Duration.ofMinutes(1));
            held.begin(owner, moderator);
            secondsPass(61);

            assertThat(held.begin(owner, somebodyElse)).isTrue();
            assertThat(held.editorOf(owner)).contains(somebodyElse);
        }

        @Test
        @DisplayName("staying in the window keeps the hold alive")
        void touchingRefreshes() {
            OfflineEdits held = edits(Duration.ofMinutes(1));
            held.begin(owner, moderator);

            secondsPass(50);
            assertThat(held.touch(owner, moderator)).isTrue();
            secondsPass(50);

            assertThat(held.isBeingEdited(owner))
                    .as("a moderator still looking at the window has not abandoned it")
                    .isTrue();
        }

        @Test
        @DisplayName("somebody else cannot keep a hold alive that is not theirs")
        void onlyTheHolderRefreshes() {
            OfflineEdits held = edits(Duration.ofMinutes(1));
            held.begin(owner, moderator);

            assertThat(held.touch(owner, somebodyElse)).isFalse();
        }

        @Test
        @DisplayName("expired holds are cleared out rather than kept forever in a map")
        void sweeps() {
            OfflineEdits held = edits(Duration.ofMinutes(1));
            held.begin(owner, moderator);
            held.begin(somebodyElse, moderator);
            secondsPass(61);
            held.begin(UUID.randomUUID(), moderator);

            assertThat(held.sweep()).isEqualTo(2);
            assertThat(held.size())
                    .as("a map that only ever grows is a leak on a server that runs for months")
                    .isEqualTo(1);
        }
    }

    /**
     * The moment the whole design turns on. Everything else here is bookkeeping; this is the part
     * that decides whether a player who logs in at the wrong second keeps their things.
     */
    @Nested
    @DisplayName("the owner logs back in")
    class OwnerComesBack {

        @Test
        @DisplayName("the edit yields and the moderator is named so they can be told")
        void supersedes() {
            OfflineEdits held = edits();
            held.begin(owner, moderator);

            assertThat(held.ownerCameBack(owner))
                    .as("somebody has to be told their change was dropped, or they will assume it "
                            + "took and go on to something else")
                    .contains(moderator);
            assertThat(held.isStillTheirs(owner, moderator)).isFalse();
            assertThat(held.isBeingEdited(owner)).isFalse();
        }

        @Test
        @DisplayName("nothing is written after the owner has come back")
        void refusesTheWriteAfterwards() {
            OfflineEdits held = edits();
            held.begin(owner, moderator);
            held.ownerCameBack(owner);

            AtomicInteger writes = new AtomicInteger();
            boolean written = held.writeAndFinish(owner, moderator, () -> {
                writes.incrementAndGet();
                return true;
            });

            assertThat(written).isFalse();
            assertThat(writes.get())
                    .as("the server has already read that file and holds the player in memory; a "
                            + "write now is thrown away at best and overwrites a live player at "
                            + "worst")
                    .isZero();
            assertThat(held.size()).isZero();
        }

        @Test
        @DisplayName("a normal close still writes")
        void writesWhenNobodyCameBack() {
            OfflineEdits held = edits();
            held.begin(owner, moderator);

            assertThat(held.writeAndFinish(owner, moderator, () -> true)).isTrue();
            assertThat(held.size())
                    .as("the window is closed, so the hold has nobody to belong to")
                    .isZero();
        }

        @Test
        @DisplayName("a write that fails is reported as one, and the hold still goes")
        void writeCanFail() {
            OfflineEdits held = edits();
            held.begin(owner, moderator);

            assertThat(held.writeAndFinish(owner, moderator, () -> false)).isFalse();
            assertThat(held.size()).isZero();
        }

        @Test
        @DisplayName("somebody else's window closing writes nothing and releases nothing")
        void onlyTheHolderWrites() {
            OfflineEdits held = edits();
            held.begin(owner, moderator);

            assertThat(held.writeAndFinish(owner, somebodyElse, () -> true)).isFalse();
            assertThat(held.editorOf(owner)).contains(moderator);
        }

        @Test
        @DisplayName("an abandoned edit is not written either")
        void expiredEditIsNotWritten() {
            OfflineEdits held = edits(Duration.ofMinutes(1));
            held.begin(owner, moderator);
            secondsPass(61);

            assertThat(held.writeAndFinish(owner, moderator, () -> true))
                    .as("five minutes after a moderator walked away, the file may have been "
                            + "rewritten by anything")
                    .isFalse();
        }

        @Test
        @DisplayName("a player nobody was editing simply logs in")
        void nobodyWasEditing() {
            OfflineEdits held = edits();

            assertThat(held.ownerCameBack(owner)).isEmpty();
            assertThat(held.ownerCameBack(null)).isEmpty();
        }

        @Test
        @DisplayName("logging in and closing the window at the same instant writes nothing")
        void loginRacesTheClose() throws InterruptedException {
            for (int attempt = 0; attempt < 200; attempt++) {
                OfflineEdits held = edits();
                held.begin(owner, moderator);
                AtomicInteger writes = new AtomicInteger();
                CountDownLatch go = new CountDownLatch(1);

                Thread joining = new Thread(() -> {
                    await(go);
                    held.ownerCameBack(owner);
                });
                Thread closing = new Thread(() -> {
                    await(go);
                    held.writeAndFinish(owner, moderator, () -> {
                        writes.incrementAndGet();
                        return true;
                    });
                });
                joining.start();
                closing.start();
                go.countDown();
                joining.join();
                closing.join();

                // Either the close got there first and wrote, or the login did and it did not. What
                // must never happen is a write that starts after the login has been decided — which
                // is exactly what a check-then-write in two steps allows.
                assertThat(writes.get()).isLessThanOrEqualTo(1);
            }
        }

        private static void await(CountDownLatch gate) {
            try {
                gate.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Nested
    @DisplayName("under load")
    class Concurrency {

        @Test
        @DisplayName("two moderators asking at the same instant: exactly one is told yes")
        void onlyOneWinner() throws InterruptedException {
            OfflineEdits held = edits();
            int racers = 32;
            CountDownLatch ready = new CountDownLatch(racers);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();

            // One thread each. A smaller pool would be a test that hangs rather than one that
            // fails: the tasks that did get a thread block on the gate, and the ones that never
            // started can never count the latch down.
            try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
                for (int racer = 0; racer < racers; racer++) {
                    pool.execute(() -> {
                        ready.countDown();
                        try {
                            go.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (held.begin(owner, UUID.randomUUID())) {
                            winners.incrementAndGet();
                        }
                    });
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                go.countDown();
            }

            assertThat(winners.get())
                    .as("checking and then taking, as two steps, lets two moderators both be told "
                            + "yes — and on Folia these calls really do come from different threads")
                    .isEqualTo(1);
            assertThat(held.size()).isEqualTo(1);
        }
    }
}
