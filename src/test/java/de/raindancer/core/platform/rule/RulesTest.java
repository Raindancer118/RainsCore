package de.raindancer.core.platform.rule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Asking a chain of reasons whether something may happen.
 *
 * <p>Two properties carry the design. <b>Order is kept and the first no wins</b>, because the cheap rules go
 * first and the one that had to touch the disk should not run when the size check already said no. And <b>a
 * refusal always says why</b> — a rule that answers no with nothing to report leaves both the player and the
 * operator with no way to find out what happened, which is the exact failure this type exists to prevent.
 */
class RulesTest {

    /** A rule that answers what it was told to, and records that it was asked. */
    private static final class Fake implements IRule<String> {
        private final String name;
        private final Verdict answer;
        private final List<String> journal;

        Fake(String name, Verdict answer, List<String> journal) {
            this.name = name;
            this.answer = answer;
            this.journal = journal;
        }

        @Override
        public Verdict judge(String subject) {
            journal.add(name);
            return answer;
        }

        @Override
        public String describe() {
            return name;
        }
    }

    private final List<String> asked = new ArrayList<>();

    private Fake yes(String name) {
        return new Fake(name, Verdict.allowed(), asked);
    }

    private Fake no(String name, String reason) {
        return new Fake(name, Verdict.refused(reason, name), asked);
    }

    @Nested
    @DisplayName("a verdict")
    class Verdicts {

        @Test
        void allowedCarriesNoReason() {
            assertThat(Verdict.allowed().isAllowed()).isTrue();
            assertThat(Verdict.allowed().isRefused()).isFalse();
            assertThat(Verdict.allowed().refusal()).isEmpty();
        }

        @Test
        void aRefusalCarriesItsKeyAndDetail() {
            Verdict refused = Verdict.refused("error.claim-too-small", 9);

            assertThat(refused.isRefused()).isTrue();
            assertThat(refused.refusal()).contains("error.claim-too-small");
            assertThat(refused.detail()).isEqualTo("9");
        }

        @Test
        void aRefusalWithNothingToSayIsRefusedOutright() {
            // The whole point of the type. A no with no reason is a support ticket nobody can answer.
            assertThatIllegalArgumentException().isThrownBy(() -> new Verdict(false, "", ""));
            assertThatIllegalArgumentException().isThrownBy(() -> new Verdict(false, "  ", "x"));
        }

        @Test
        void combiningKeepsTheFirstRefusal() {
            Verdict first = Verdict.refused("first");
            Verdict second = Verdict.refused("second");

            assertThat(first.and(second)).isEqualTo(first);
            assertThat(Verdict.allowed().and(second)).isEqualTo(second);
            assertThat(Verdict.allowed().and(Verdict.allowed()).isAllowed()).isTrue();
        }

        @Test
        void aNullDetailIsEmptyRatherThanTheWordNull() {
            // These end up in a sentence a player reads.
            assertThat(Verdict.refused("nope", null).detail()).isEmpty();
        }
    }

    @Nested
    @DisplayName("a chain")
    class Chains {

        @Test
        void withNothingInItAllowsEverything() {
            assertThat(Rules.<String>of().judge("anything").isAllowed()).isTrue();
        }

        @Test
        void asksInOrderAndStopsAtTheFirstNo() {
            Rules<String> rules = Rules.of(yes("cheap"), no("size", "too-small"), yes("expensive"));

            Verdict verdict = rules.judge("a claim");

            assertThat(verdict.refusal()).contains("too-small");
            assertThat(asked)
                    .as("the expensive rule must not run once something has already said no")
                    .containsExactly("cheap", "size");
        }

        @Test
        void asksEveryRuleWhenNoneObjects() {
            Rules.of(yes("one"), yes("two"), yes("three")).judge("fine");
            assertThat(asked).containsExactly("one", "two", "three");
        }

        @Test
        void canReportEveryRefusalAtOnce() {
            Rules<String> rules = Rules.of(no("size", "too-small"), yes("name"), no("zone", "in-a-zone"));

            List<Verdict> refusals = rules.judgeAll("a claim");

            assertThat(refusals).extracting(Verdict::reason).containsExactly("too-small", "in-a-zone");
            assertThat(asked).as("judgeAll asks all of them").hasSize(3);
        }

        @Test
        void namesWhoObjected() {
            Rules<String> rules = Rules.of(yes("first"), no("second", "nope"));

            assertThat(rules.firstObjector("x")).get()
                    .extracting(IRule::describe).isEqualTo("second");
        }

        @Test
        void namesNobodyWhenNobodyObjects() {
            assertThat(Rules.of(yes("only")).firstObjector("x")).isEmpty();
        }
    }

    @Nested
    @DisplayName("extending a chain")
    class Extending {

        @Test
        void leavesTheOriginalAlone() {
            // The point of immutability here: one caller adding a rule must not change what everybody else
            // is judged by.
            Rules<String> base = Rules.of(yes("shared"));
            Rules<String> longer = base.and(no("extra", "nope"));

            assertThat(base.size()).isEqualTo(1);
            assertThat(longer.size()).isEqualTo(2);
            assertThat(base.judge("x").isAllowed()).isTrue();
            assertThat(longer.judge("x").isRefused()).isTrue();
        }

        @Test
        void putsTheNewRuleLast() {
            Rules.of(yes("first")).and(yes("second")).judge("x");
            assertThat(asked).containsExactly("first", "second");
        }

        @Test
        void theListOfRulesCannotBeChangedFromOutside() {
            Rules<String> rules = Rules.of(yes("only"));
            assertThat(catchThrowable(() -> rules.all().add(yes("sneaky"))))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void aLambdaRuleStillDescribesItselfAsSomething() {
        IRule<String> lambda = subject -> Verdict.allowed();
        assertThat(lambda.describe()).isNotBlank();
    }
}
