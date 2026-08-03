package de.raindancer.core.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Asking a player to type something, when more than one plugin wants to.
 *
 * <h2>Why this had to be shared</h2>
 * Three of these already exist: {@code SettingsChatInput} here, {@code ChatInputService} in the
 * claims module and {@code ChatInputHandler} in the Hunger Games. Each registers its own chat
 * listener and each swallows the next line the player types. Installed together they fight: a
 * player answering the claims module's "what should this claim be called?" has their answer eaten
 * by whichever listener ran first, and the other prompt waits for ever.
 *
 * <p>It is the action bar problem in a different costume — one player, one next line of chat,
 * several plugins wanting it — and the answer is the same. One owner, and plugins ask.
 */
class ChatPromptsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private AtomicLong clock;
    private ChatPrompts prompts;
    private List<String> answered;
    private List<String> cancelled;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000L);
        prompts = new ChatPrompts(clock::get);
        answered = new ArrayList<>();
        cancelled = new ArrayList<>();
    }

    private void ask(UUID player, String owner) {
        prompts.ask(player, owner, Duration.ofMinutes(2), answered::add, () -> cancelled.add(owner));
    }

    // ------------------------------------------------------------------ answering

    @Nested
    @DisplayName("answering")
    class Answering {

        @Test
        @DisplayName("the line goes to whoever asked")
        void answersTheAsker() {
            ask(ALICE, "claims");
            assertThat(prompts.offer(ALICE, "my base")).isEqualTo(PromptResult.ANSWERED);
            assertThat(answered).containsExactly("my base");
        }

        @Test
        @DisplayName("a line from somebody nobody asked is not ours")
        void ignoresEverybodyElse() {
            ask(ALICE, "claims");
            assertThat(prompts.offer(BOB, "just chatting"))
                    .as("otherwise a prompt would swallow another player's chat")
                    .isEqualTo(PromptResult.NOT_WAITING);
            assertThat(answered).isEmpty();
        }

        @Test
        @DisplayName("only the next line — the one after is theirs again")
        void answersOnce() {
            ask(ALICE, "claims");
            prompts.offer(ALICE, "my base");
            assertThat(prompts.offer(ALICE, "hello everyone"))
                    .isEqualTo(PromptResult.NOT_WAITING);
            assertThat(answered).containsExactly("my base");
        }

        @Test
        @DisplayName("cancel is a word, and does not reach the plugin as an answer")
        void cancelling() {
            ask(ALICE, "claims");
            assertThat(prompts.offer(ALICE, "cancel")).isEqualTo(PromptResult.CANCELLED);
            assertThat(answered).isEmpty();
            assertThat(cancelled).containsExactly("claims");
        }

        @Test
        @DisplayName("an answer that throws does not leave the player stuck being asked")
        void survivesABrokenAnswer() {
            prompts.ask(ALICE, "claims", Duration.ofMinutes(2),
                    line -> {
                        throw new IllegalStateException("no");
                    }, null);
            assertThat(prompts.offer(ALICE, "anything")).isEqualTo(PromptResult.FAILED);
            assertThat(prompts.isWaiting(ALICE))
                    .as("a plugin's bug must not leave somebody unable to use chat")
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------ the collision

    /** The reason this class exists. */
    @Nested
    @DisplayName("when two plugins ask at once")
    class Collisions {

        @Test
        @DisplayName("the second is refused rather than quietly replacing the first")
        void secondAskIsRefused() {
            ask(ALICE, "claims");
            boolean second = prompts.ask(ALICE, "ghasts", Duration.ofMinutes(2),
                    answered::add, null);

            assertThat(second)
                    .as("silently replacing it is how a player answers one question and another "
                            + "plugin waits for ever")
                    .isFalse();
            assertThat(prompts.waitingFor(ALICE)).contains("claims");
        }

        @Test
        @DisplayName("and the answer still goes to the one that got there first")
        void firstAskerKeepsIt() {
            ask(ALICE, "claims");
            prompts.ask(ALICE, "ghasts", Duration.ofMinutes(2),
                    line -> answered.add("WRONG: " + line), null);

            prompts.offer(ALICE, "my base");
            assertThat(answered).containsExactly("my base");
        }

        @Test
        @DisplayName("once the first is answered the next plugin may ask")
        void freedAfterAnswering() {
            ask(ALICE, "claims");
            prompts.offer(ALICE, "my base");
            assertThat(prompts.ask(ALICE, "ghasts", Duration.ofMinutes(2), answered::add, null))
                    .isTrue();
        }

        @Test
        @DisplayName("a plugin can withdraw its own question")
        void withdrawing() {
            ask(ALICE, "claims");
            assertThat(prompts.withdraw(ALICE, "claims")).isTrue();
            assertThat(prompts.isWaiting(ALICE)).isFalse();
        }

        @Test
        @DisplayName("but not somebody else's")
        void cannotWithdrawAnothersQuestion() {
            ask(ALICE, "claims");
            assertThat(prompts.withdraw(ALICE, "ghasts")).isFalse();
            assertThat(prompts.waitingFor(ALICE)).contains("claims");
        }

        @Test
        @DisplayName("two players are asked independently")
        void playersAreIndependent() {
            ask(ALICE, "claims");
            assertThat(prompts.ask(BOB, "ghasts", Duration.ofMinutes(2), answered::add, null))
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------ giving up

    @Nested
    @DisplayName("when nobody answers")
    class Expiry {

        @Test
        @DisplayName("the question expires, and the plugin is told")
        void expires() {
            ask(ALICE, "claims");
            clock.addAndGet(Duration.ofMinutes(3).toMillis());

            assertThat(prompts.isWaiting(ALICE)).isFalse();
            assertThat(prompts.offer(ALICE, "too late")).isEqualTo(PromptResult.NOT_WAITING);
        }

        @Test
        @DisplayName("sweeping tells whoever asked that it gave up")
        void sweepingCancels() {
            ask(ALICE, "claims");
            clock.addAndGet(Duration.ofMinutes(3).toMillis());
            prompts.sweep();

            assertThat(cancelled)
                    .as("a plugin waiting on an answer needs to know none is coming, or it holds "
                            + "whatever it captured for ever")
                    .containsExactly("claims");
        }

        @Test
        @DisplayName("an expired question does not block the next one")
        void expiryFreesThePlayer() {
            ask(ALICE, "claims");
            clock.addAndGet(Duration.ofMinutes(3).toMillis());
            assertThat(prompts.ask(ALICE, "ghasts", Duration.ofMinutes(2), answered::add, null))
                    .isTrue();
        }

        @Test
        @DisplayName("a player who logs out is not still being asked when they come back")
        void forgetsPlayers() {
            ask(ALICE, "claims");
            prompts.forget(ALICE);
            assertThat(prompts.isWaiting(ALICE)).isFalse();
            assertThat(cancelled).containsExactly("claims");
        }
    }

    // ------------------------------------------------------------------ misuse

    @Test
    @DisplayName("nulls do not throw")
    void survivesNulls() {
        assertThatCode(() -> {
            prompts.ask(null, "claims", Duration.ofMinutes(1), answered::add, null);
            prompts.ask(ALICE, null, Duration.ofMinutes(1), answered::add, null);
            prompts.ask(ALICE, "claims", Duration.ofMinutes(1), null, null);
            prompts.offer(null, "x");
            prompts.offer(ALICE, null);
            prompts.forget(null);
        }).doesNotThrowAnyException();
        assertThat(prompts.isWaiting(ALICE)).isFalse();
    }

    @Test
    @DisplayName("a question with no life is not asked at all")
    void refusesAZeroLifetime() {
        assertThat(prompts.ask(ALICE, "claims", Duration.ZERO, answered::add, null)).isFalse();
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("two plugins asking at the same instant: exactly one wins")
    void isSafeFromEveryThread() throws Exception {
        for (int round = 0; round < 500; round++) {
            setUp();
            AtomicInteger won = new AtomicInteger();
            int threads = 4;
            CountDownLatch go = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                for (int thread = 0; thread < threads; thread++) {
                    int id = thread;
                    pool.submit(() -> {
                        go.await();
                        if (prompts.ask(ALICE, "plugin-" + id, Duration.ofMinutes(1),
                                answered::add, null)) {
                            won.incrementAndGet();
                        }
                        return null;
                    });
                }
                go.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(won.get())
                    .as("round %d: two plugins both believing they own the next line is the bug",
                            round)
                    .isEqualTo(1);
        }
    }
}
