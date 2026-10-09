package de.raindancer.core.moderation.rules;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RulePenaltyTest {

    @AfterEach
    void clear() {
        ServerRules.clear();
    }

    @Test
    @DisplayName("a penalty is read the way a moderator writes it")
    void parsing() {
        assertThat(RulePenalty.parse("warn")).contains(new RulePenalty(PunishmentKind.WARNING, null));
        assertThat(RulePenalty.parse("Kick")).contains(new RulePenalty(PunishmentKind.KICK, null));
        assertThat(RulePenalty.parse("mute 1h")).contains(new RulePenalty(PunishmentKind.MUTE, Duration.ofHours(1)));
        assertThat(RulePenalty.parse("ban 3d")).contains(new RulePenalty(PunishmentKind.BAN, Duration.ofDays(3)));
        assertThat(RulePenalty.parse("ban")).contains(new RulePenalty(PunishmentKind.BAN, null));
        assertThat(RulePenalty.parse("ban perm")).contains(new RulePenalty(PunishmentKind.BAN, null));
        assertThat(RulePenalty.parse("freeze 30m")).contains(new RulePenalty(PunishmentKind.FREEZE, Duration.ofMinutes(30)));
        assertThat(RulePenalty.parse("hug")).isEmpty();
        assertThat(RulePenalty.parse("mute soon")).isEmpty();
        assertThat(RulePenalty.parse("warn 1h")).as("a warning has no length").isEmpty();
    }

    @Test
    @DisplayName("a whole ladder is one line, commas between rungs, and written back the same way")
    void ladders() {
        List<RulePenalty> ladder = RulePenalty.ladder("warn, mute 1h, ban 3d, ban").orElseThrow();
        assertThat(ladder).hasSize(4);
        assertThat(RulePenalty.write(ladder)).isEqualTo("warn, mute 1h, ban 3d, ban");
        assertThat(RulePenalty.ladder("warn, nonsense")).as("one bad rung refuses the line").isEmpty();
        assertThat(RulePenalty.ladder("  ")).contains(List.of());
    }

    @Test
    @DisplayName("it says what happens in a sentence a player understands")
    void wording() {
        assertThat(RulePenalty.parse("warn").orElseThrow().describe()).isEqualTo("a warning");
        assertThat(RulePenalty.parse("kick").orElseThrow().describe()).isEqualTo("a kick");
        assertThat(RulePenalty.parse("mute 1h").orElseThrow().describe()).isEqualTo("muted for 1 hour");
        assertThat(RulePenalty.parse("ban").orElseThrow().describe()).isEqualTo("banned for good");
        assertThat(RulePenalty.describe(RulePenalty.ladder("warn, mute 1h, ban").orElseThrow()))
                .isEqualTo("1st: a warning · 2nd: muted for 1 hour · then: banned for good");
        assertThat(RulePenalty.describe(RulePenalty.ladder("ban").orElseThrow())).isEqualTo("every time: banned for good");
        assertThat(RulePenalty.ordinal(12)).isEqualTo("12th");
    }

    @Test
    @DisplayName("the nth offence takes the nth rung; past the top it stays on the top")
    void rungs() {
        ServerRule rule = new ServerRule("a1", 2, "No griefing", "Leave it alone.",
                RulePenalty.ladder("warn, ban 3d, ban").orElseThrow());
        assertThat(rule.forOffence(0).orElseThrow().kind()).isEqualTo(PunishmentKind.WARNING);
        assertThat(rule.forOffence(1).orElseThrow().length()).isEqualTo(Duration.ofDays(3));
        assertThat(rule.forOffence(7).orElseThrow().isPermanent()).isTrue();
        assertThat(new ServerRule("b", 1, "t", "x", List.of()).forOffence(0)).isEmpty();
    }

    @Test
    @DisplayName("the server's rules are whatever the plugin keeping them says; nothing when none does")
    void registry() {
        assertThat(ServerRules.current()).isEmpty();
        ServerRules.provide(() -> List.of(new ServerRule("a1", 1, "Be kind", "Nice.", List.of())));
        assertThat(ServerRules.current()).extracting(ServerRule::title).containsExactly("Be kind");
        assertThat(ServerRules.byNumber(1)).isPresent();
        assertThat(ServerRules.byId("a1")).isPresent();
        assertThat(ServerRules.byNumber(2)).isEmpty();
    }
}
