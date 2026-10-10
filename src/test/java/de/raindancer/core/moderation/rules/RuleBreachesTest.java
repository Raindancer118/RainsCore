package de.raindancer.core.moderation.rules;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBreachesTest {

    private static final ServerRule CHEATING = new ServerRule("c1", 4, "No cheating", "No hacked clients.",
            List.of(new RulePenalty(PunishmentKind.BAN, Duration.ofDays(14)), new RulePenalty(PunishmentKind.BAN, null)));

    @AfterEach
    void clear() {
        RuleBreaches.clear();
        ServerRules.clear();
    }

    @Test
    @DisplayName("without a plugin handing out rule punishments, a breach is not handed out and says so")
    void nobodyJudges() {
        assertThat(RuleBreaches.available()).isFalse();
        assertThat(RuleBreaches.breach(UUID.randomUUID(), "Cheater", CHEATING, "Anti-Cheat", "fly")).isEmpty();
    }

    @Test
    @DisplayName("a breach goes to whoever judges them, and what they handed out comes back")
    void judged() {
        UUID cheater = UUID.randomUUID();
        RuleBreaches.Judge judge = (subject, name, rule, by, note) ->
                rule.forOffence(0).map(penalty -> new RuleBreaches.Outcome(penalty, 1));
        RuleBreaches.provide(judge);

        assertThat(RuleBreaches.available()).isTrue();
        assertThat(RuleBreaches.breach(cheater, "Cheater", CHEATING, "Anti-Cheat", "fly"))
                .contains(new RuleBreaches.Outcome(new RulePenalty(PunishmentKind.BAN, Duration.ofDays(14)), 1));

        RuleBreaches.withdraw(judge);
        assertThat(RuleBreaches.available()).isFalse();
    }

    @Test
    @DisplayName("a judge that throws costs that one breach, not the caller")
    void brokenJudge() {
        RuleBreaches.provide((subject, name, rule, by, note) -> {
            throw new IllegalStateException("broken");
        });
        assertThat(RuleBreaches.breach(UUID.randomUUID(), "Cheater", CHEATING, "Anti-Cheat", "fly")).isEmpty();
    }

    @Test
    @DisplayName("withdrawing somebody else's judge leaves the current one in place")
    void withdrawOnlyYourOwn() {
        RuleBreaches.Judge mine = (subject, name, rule, by, note) -> Optional.empty();
        RuleBreaches.provide(mine);
        RuleBreaches.withdraw((subject, name, rule, by, note) -> Optional.empty());
        assertThat(RuleBreaches.available()).isTrue();
    }

    @Test
    @DisplayName("the rule about something is found by a word in its title first, then in its text")
    void about() {
        ServerRules.provide(() -> List.of(
                new ServerRule("a", 1, "Keep the server running", "No lag machines or crash exploits.", List.of()),
                new ServerRule("b", 2, "No Cheating", "No hacked clients, x-ray or exploits.", List.of()),
                new ServerRule("c", 3, "Be kind", "No insults.", List.of())));

        assertThat(ServerRules.about(List.of("cheat", "hack"))).map(ServerRule::id).contains("b");
        assertThat(ServerRules.about(List.of("exploit"))).as("no title says it: the first text that does")
                .map(ServerRule::id).contains("a");
        assertThat(ServerRules.about(List.of("grief"))).isEmpty();
    }
}
