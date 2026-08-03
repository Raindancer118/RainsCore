package de.raindancer.core.content.vote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asking everybody a question and counting the answers.
 *
 * <h2>Why it is worth building properly</h2>
 * Because the naive version is a HashMap and a scheduled task, and it is wrong in ways that only
 * show up in front of the whole server: somebody votes twice, somebody who left is still counted,
 * the tally is read while it is being written, or a vote that ends in a tie declares a winner
 * anyway. Every one of those is a public argument.
 *
 * <p>All of it against an injected clock, because a vote is mostly a question about time and waiting
 * five minutes per test is not a test.
 */
@DisplayName("votes")
class VotesTest {

    private final AtomicLong now = new AtomicLong(1_000L);

    private Votes votes() {
        return new Votes(now::get);
    }

    private static final UUID OP = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    private Vote open(Votes votes) {
        return votes.open(OP, "Should we reset the farm world?", List.of("Yes", "No"),
                Duration.ofMinutes(2)).orElseThrow();
    }

    // ------------------------------------------------------------------ starting one

    @Nested
    @DisplayName("starting one")
    class Opening {

        @Test
        @DisplayName("a question with two answers and a deadline")
        void opensAVote() {
            Votes votes = votes();
            Vote vote = open(votes);

            assertThat(vote.question()).isEqualTo("Should we reset the farm world?");
            assertThat(vote.options()).containsExactly("Yes", "No");
            assertThat(vote.isOpen(now.get())).isTrue();
            assertThat(votes.open()).containsExactly(vote);
        }

        @Test
        @DisplayName("fewer than two answers is not a vote")
        void needsAChoice() {
            Votes votes = votes();
            assertThat(votes.open(OP, "Well?", List.of("Yes"), Duration.ofMinutes(1)))
                    .as("a question with one answer is an announcement")
                    .isEmpty();
            assertThat(votes.open(OP, "Well?", List.of(), Duration.ofMinutes(1))).isEmpty();
        }

        @Test
        @DisplayName("more than two answers is fine")
        void takesManyOptions() {
            Votes votes = votes();
            Vote vote = votes.open(OP, "Which world?",
                    List.of("Desert", "Jungle", "Islands", "Mountains"),
                    Duration.ofMinutes(5)).orElseThrow();
            assertThat(vote.options()).hasSize(4);
        }

        @Test
        @DisplayName("a question with nothing in it is refused")
        void needsAQuestion() {
            Votes votes = votes();
            assertThat(votes.open(OP, "  ", List.of("Yes", "No"), Duration.ofMinutes(1))).isEmpty();
        }

        @Test
        @DisplayName("two answers spelled the same are one answer")
        void refusesDuplicateOptions() {
            Votes votes = votes();
            assertThat(votes.open(OP, "Well?", List.of("Yes", "yes"), Duration.ofMinutes(1)))
                    .as("two buttons with the same word on them split the vote for no reason")
                    .isEmpty();
        }

        @Test
        @DisplayName("no deadline at all is refused")
        void needsADeadline() {
            Votes votes = votes();
            assertThat(votes.open(OP, "Well?", List.of("Yes", "No"), null))
                    .as("a vote that never closes is a vote nobody ever acts on")
                    .isEmpty();
            assertThat(votes.open(OP, "Well?", List.of("Yes", "No"), Duration.ZERO)).isEmpty();
        }

        @Test
        @DisplayName("several votes can run at once and do not mix")
        void severalAtOnce() {
            Votes votes = votes();
            Vote first = open(votes);
            Vote second = votes.open(OP, "Pizza?", List.of("Yes", "No"),
                    Duration.ofMinutes(1)).orElseThrow();

            votes.cast(first.id(), ALICE, "Yes");
            assertThat(votes.tally(second.id()).orElseThrow().totalCast()).isZero();
            assertThat(first.id()).isNotEqualTo(second.id());
        }
    }

    // ------------------------------------------------------------------ voting

    @Nested
    @DisplayName("casting a vote")
    class Casting {

        @Test
        @DisplayName("a vote is counted")
        void countsIt() {
            Votes votes = votes();
            Vote vote = open(votes);

            assertThat(votes.cast(vote.id(), ALICE, "Yes")).isEqualTo(Ballot.COUNTED);
            assertThat(votes.tally(vote.id()).orElseThrow().votesFor("Yes")).isEqualTo(1);
        }

        @Test
        @DisplayName("somebody can change their mind, and it is still one vote")
        void changingYourMind() {
            Votes votes = votes();
            Vote vote = open(votes);
            votes.cast(vote.id(), ALICE, "Yes");

            assertThat(votes.cast(vote.id(), ALICE, "No")).isEqualTo(Ballot.CHANGED);
            Tally tally = votes.tally(vote.id()).orElseThrow();
            assertThat(tally.votesFor("Yes")).isZero();
            assertThat(tally.votesFor("No")).isEqualTo(1);
            assertThat(tally.totalCast())
                    .as("counting a changed vote twice is how a vote gets more answers than voters")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("voting for the same thing again is not a change")
        void votingTwiceTheSameWay() {
            Votes votes = votes();
            Vote vote = open(votes);
            votes.cast(vote.id(), ALICE, "Yes");

            assertThat(votes.cast(vote.id(), ALICE, "Yes")).isEqualTo(Ballot.ALREADY);
            assertThat(votes.tally(vote.id()).orElseThrow().totalCast()).isEqualTo(1);
        }

        @Test
        @DisplayName("an answer that is not on the ballot is refused")
        void refusesUnknownOptions() {
            Votes votes = votes();
            Vote vote = open(votes);
            assertThat(votes.cast(vote.id(), ALICE, "Maybe")).isEqualTo(Ballot.NOT_AN_OPTION);
            assertThat(votes.tally(vote.id()).orElseThrow().totalCast()).isZero();
        }

        @Test
        @DisplayName("the answer can be given in any case")
        void isCaseInsensitive() {
            Votes votes = votes();
            Vote vote = open(votes);
            assertThat(votes.cast(vote.id(), ALICE, "yes")).isEqualTo(Ballot.COUNTED);
            assertThat(votes.tally(vote.id()).orElseThrow().votesFor("Yes")).isEqualTo(1);
        }

        @Test
        @DisplayName("voting in a vote nobody started is refused")
        void refusesUnknownVotes() {
            assertThat(votes().cast(UUID.randomUUID(), ALICE, "Yes")).isEqualTo(Ballot.NO_SUCH_VOTE);
        }

        @Test
        @DisplayName("a vote that has run out is closed to new answers")
        void refusesLateVotes() {
            Votes votes = votes();
            Vote vote = open(votes);
            now.addAndGet(Duration.ofMinutes(3).toMillis());

            assertThat(votes.cast(vote.id(), ALICE, "Yes"))
                    .as("a vote counted after the deadline is a result somebody can argue with")
                    .isEqualTo(Ballot.CLOSED);
        }

        @Test
        @DisplayName("only the people allowed to vote can")
        void respectsWhoMayVote() {
            Votes votes = votes();
            Vote vote = votes.open(OP, "Town business?", List.of("Yes", "No"),
                    Duration.ofMinutes(2), List.of(ALICE, BOB)).orElseThrow();

            assertThat(votes.cast(vote.id(), ALICE, "Yes")).isEqualTo(Ballot.COUNTED);
            assertThat(votes.cast(vote.id(), CAROL, "Yes"))
                    .as("a town council vote is not a server-wide one")
                    .isEqualTo(Ballot.NOT_YOURS);
        }

        @Test
        @DisplayName("who voted is known, but not what they voted for")
        void secrecy() {
            Votes votes = votes();
            Vote vote = open(votes);
            votes.cast(vote.id(), ALICE, "Yes");

            assertThat(votes.hasVoted(vote.id(), ALICE)).isTrue();
            assertThat(votes.hasVoted(vote.id(), BOB)).isFalse();
        }
    }

    // ------------------------------------------------------------------ the result

    @Nested
    @DisplayName("the result")
    class Results {

        @Test
        @DisplayName("the most answers wins")
        void mostVotesWins() {
            Votes votes = votes();
            Vote vote = open(votes);
            votes.cast(vote.id(), ALICE, "Yes");
            votes.cast(vote.id(), BOB, "Yes");
            votes.cast(vote.id(), CAROL, "No");

            Tally tally = votes.tally(vote.id()).orElseThrow();
            assertThat(tally.winner()).contains("Yes");
            assertThat(tally.isTie()).isFalse();
            assertThat(tally.totalCast()).isEqualTo(3);
        }

        @Test
        @DisplayName("a tie is a tie, not a coin toss")
        void tiesAreTies() {
            Votes votes = votes();
            Vote vote = open(votes);
            votes.cast(vote.id(), ALICE, "Yes");
            votes.cast(vote.id(), BOB, "No");

            Tally tally = votes.tally(vote.id()).orElseThrow();
            assertThat(tally.isTie()).isTrue();
            assertThat(tally.winner())
                    .as("declaring a winner out of a tie is how a vote becomes an argument")
                    .isEmpty();
            assertThat(tally.leaders()).containsExactlyInAnyOrder("Yes", "No");
        }

        @Test
        @DisplayName("nobody voting is not a win for the first option")
        void nobodyVoted() {
            Votes votes = votes();
            Vote vote = open(votes);
            Tally tally = votes.tally(vote.id()).orElseThrow();

            assertThat(tally.totalCast()).isZero();
            assertThat(tally.winner()).isEmpty();
        }

        @Test
        @DisplayName("a share is worked out of what was actually cast")
        void shares() {
            Votes votes = votes();
            Vote vote = open(votes);
            votes.cast(vote.id(), ALICE, "Yes");
            votes.cast(vote.id(), BOB, "Yes");
            votes.cast(vote.id(), CAROL, "No");

            Tally tally = votes.tally(vote.id()).orElseThrow();
            assertThat(tally.shareOf("Yes")).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
            assertThat(tally.shareOf("No")).isCloseTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("a share of nothing is nothing rather than a division by zero")
        void shareOfNothing() {
            Votes votes = votes();
            Vote vote = open(votes);
            assertThat(votes.tally(vote.id()).orElseThrow().shareOf("Yes")).isZero();
        }
    }

    // ------------------------------------------------------------------ ending

    @Nested
    @DisplayName("ending one")
    class Closing {

        @Test
        @DisplayName("it closes itself when the time runs out")
        void closesOnTime() {
            Votes votes = votes();
            Vote vote = open(votes);
            now.addAndGet(Duration.ofMinutes(3).toMillis());

            assertThat(votes.sweep()).containsExactly(vote.id());
            assertThat(votes.open()).isEmpty();
            assertThat(votes.tally(vote.id()))
                    .as("the answer has to outlive the vote, or nobody can act on it")
                    .isPresent();
        }

        @Test
        @DisplayName("sweeping twice does not announce the same result twice")
        void closesOnlyOnce() {
            Votes votes = votes();
            open(votes);
            now.addAndGet(Duration.ofMinutes(3).toMillis());

            assertThat(votes.sweep()).hasSize(1);
            assertThat(votes.sweep())
                    .as("a timer calling this every second must not announce a result every second")
                    .isEmpty();
        }

        @Test
        @DisplayName("whoever started it can end it early")
        void canBeEndedEarly() {
            Votes votes = votes();
            Vote vote = open(votes);

            assertThat(votes.close(vote.id())).isTrue();
            assertThat(votes.cast(vote.id(), ALICE, "Yes")).isEqualTo(Ballot.CLOSED);
            assertThat(votes.close(vote.id()))
                    .as("closing an already-closed vote is not an error, but it is not a change")
                    .isFalse();
        }

        @Test
        @DisplayName("results are kept, and old ones eventually let go")
        void keepsResultsForAWhile() {
            Votes votes = votes();
            Vote vote = open(votes);
            votes.close(vote.id());

            assertThat(votes.tally(vote.id())).isPresent();
            now.addAndGet(Duration.ofDays(2).toMillis());
            votes.sweep();
            assertThat(votes.tally(vote.id()))
                    .as("every vote ever held, kept for ever, is a leak with a long fuse")
                    .isEmpty();
        }

        @Test
        @DisplayName("how long is left can be asked, for a bossbar or a countdown")
        void saysHowLongIsLeft() {
            Votes votes = votes();
            Vote vote = open(votes);
            now.addAndGet(Duration.ofMinutes(1).toMillis());

            Optional<Duration> left = vote.timeLeft(now.get());
            assertThat(left).isPresent();
            assertThat(left.orElseThrow().toSeconds()).isEqualTo(60);
        }
    }
}
