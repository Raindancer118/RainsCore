package de.raindancer.core.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the lengths of time people actually type.
 *
 * <h2>Why this is worth a package</h2>
 * Because {@code Duration.parse} wants {@code PT30M} and nobody has ever typed that into a chat box.
 * People type {@code 2min}, {@code 2m}, {@code 90s}, {@code 1h30m}, {@code 2 weeks}, {@code perm} —
 * and every plugin that takes a length of time grew its own half-parser that understands three of
 * those and silently misreads the rest.
 *
 * <p>The interesting part is the ambiguity. {@code m} means minutes to everybody who has ever typed
 * it into a ban command, and {@code month} obviously does not — so the two have to be told apart on
 * purpose rather than by whichever regex was written first. Getting that backwards turns a
 * two-minute mute into a two-month one.
 */
@DisplayName("reading times")
class TimesTest {

    // ------------------------------------------------------------------ the simple cases

    @Nested
    @DisplayName("one number and one unit")
    class Simple {

        @Test
        @DisplayName("seconds, however they are written")
        void seconds() {
            assertThat(Times.parse("30s")).contains(Duration.ofSeconds(30));
            assertThat(Times.parse("30 sec")).contains(Duration.ofSeconds(30));
            assertThat(Times.parse("30 seconds")).contains(Duration.ofSeconds(30));
            assertThat(Times.parse("1 second")).contains(Duration.ofSeconds(1));
        }

        @Test
        @DisplayName("m is minutes, because that is what everybody means by it")
        void minutes() {
            assertThat(Times.parse("2m")).contains(Duration.ofMinutes(2));
            assertThat(Times.parse("2min")).contains(Duration.ofMinutes(2));
            assertThat(Times.parse("2mins")).contains(Duration.ofMinutes(2));
            assertThat(Times.parse("2 minutes"))
                    .as("a two-minute mute must never come out as two months")
                    .contains(Duration.ofMinutes(2));
        }

        @Test
        @DisplayName("hours, days and weeks")
        void theRestOfTheEasyOnes() {
            assertThat(Times.parse("3h")).contains(Duration.ofHours(3));
            assertThat(Times.parse("3 hours")).contains(Duration.ofHours(3));
            assertThat(Times.parse("7d")).contains(Duration.ofDays(7));
            assertThat(Times.parse("2w")).contains(Duration.ofDays(14));
            assertThat(Times.parse("2 weeks")).contains(Duration.ofDays(14));
        }

        @Test
        @DisplayName("a bare number is taken as seconds")
        void bareNumbers() {
            assertThat(Times.parse("90")).contains(Duration.ofSeconds(90));
        }

        @Test
        @DisplayName("case and spaces do not matter")
        void isForgiving() {
            assertThat(Times.parse("  2 Minutes  ")).contains(Duration.ofMinutes(2));
            assertThat(Times.parse("2H")).contains(Duration.ofHours(2));
        }
    }

    // ------------------------------------------------------------------ the ambiguous one

    @Nested
    @DisplayName("months, which are the whole problem")
    class Months {

        @Test
        @DisplayName("mo and month mean months")
        void spelledOut() {
            assertThat(Times.parse("2mo")).isPresent();
            assertThat(Times.parse("2mo").orElseThrow())
                    .isGreaterThan(Duration.ofDays(50));
            assertThat(Times.parse("2 months").orElseThrow())
                    .isGreaterThan(Duration.ofDays(50));
        }

        @Test
        @DisplayName("a capital M means months, because a shell-style shorthand needs one")
        void capitalM() {
            assertThat(Times.parse("2M").orElseThrow())
                    .as("lowercase m is minutes and something has to mean months; the capital is "
                            + "the convention people already know from cron and from ban plugins")
                    .isGreaterThan(Duration.ofDays(50));
            assertThat(Times.parse("2m")).contains(Duration.ofMinutes(2));
        }

        @Test
        @DisplayName("years too")
        void years() {
            assertThat(Times.parse("1y").orElseThrow()).isGreaterThan(Duration.ofDays(360));
            assertThat(Times.parse("1 year").orElseThrow()).isGreaterThan(Duration.ofDays(360));
        }

        @Test
        @DisplayName("as a duration a month is an approximation, and it says so")
        void isAnApproximation() {
            assertThat(Times.isApproximate("1mo"))
                    .as("a month is not a fixed number of days; a caller that needs the real "
                            + "answer has to know to ask for it")
                    .isTrue();
            assertThat(Times.isApproximate("30d")).isFalse();
        }

        @Test
        @DisplayName("asked against a real date, a month lands on the same day next month")
        void isExactWhenItMatters() {
            Instant january31 = ZonedDateTime.of(2026, 1, 31, 12, 0, 0, 0, ZoneOffset.UTC)
                    .toInstant();

            Instant oneMonth = Times.after(january31, "1mo").orElseThrow();
            assertThat(ZonedDateTime.ofInstant(oneMonth, ZoneOffset.UTC).getMonthValue())
                    .as("a one-month ban should end in February, not 'in thirty days'")
                    .isEqualTo(2);
            assertThat(ZonedDateTime.ofInstant(oneMonth, ZoneOffset.UTC).getDayOfMonth())
                    .as("and the 31st of February has to become the 28th rather than March")
                    .isEqualTo(28);
        }

        @Test
        @DisplayName("a year against a real date lands on the same day next year")
        void yearsAreExactToo() {
            Instant when = ZonedDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC).toInstant();
            Instant later = Times.after(when, "1y").orElseThrow();
            assertThat(ZonedDateTime.ofInstant(later, ZoneOffset.UTC).getYear()).isEqualTo(2027);
            assertThat(ZonedDateTime.ofInstant(later, ZoneOffset.UTC).getDayOfMonth()).isEqualTo(15);
        }
    }

    // ------------------------------------------------------------------ several parts

    @Nested
    @DisplayName("more than one part")
    class Compound {

        @Test
        @DisplayName("parts add up")
        void addsThemUp() {
            assertThat(Times.parse("1h30m")).contains(Duration.ofMinutes(90));
            assertThat(Times.parse("1d12h")).contains(Duration.ofHours(36));
            assertThat(Times.parse("1h 30m 15s"))
                    .contains(Duration.ofMinutes(90).plusSeconds(15));
        }

        @Test
        @DisplayName("the same unit twice adds rather than replacing")
        void repeatsAddUp() {
            assertThat(Times.parse("30m30m")).contains(Duration.ofMinutes(60));
        }
    }

    // ------------------------------------------------------------------ saying no

    @Nested
    @DisplayName("what it will not accept")
    class Refusing {

        @Test
        @DisplayName("nonsense is empty rather than a guess")
        void refusesNonsense() {
            assertThat(Times.parse("tomorrow")).isEmpty();
            assertThat(Times.parse("")).isEmpty();
            assertThat(Times.parse(null)).isEmpty();
            assertThat(Times.parse("5x")).isEmpty();
        }

        @Test
        @DisplayName("half-nonsense is refused rather than partly understood")
        void refusesPartialNonsense() {
            assertThat(Times.parse("1h and a bit"))
                    .as("reading '1h' out of that and dropping the rest is how somebody gets a "
                            + "ban of a length nobody asked for")
                    .isEmpty();
        }

        @Test
        @DisplayName("a negative or a zero is refused")
        void refusesNothing() {
            assertThat(Times.parse("-5m")).isEmpty();
            assertThat(Times.parse("0s")).isEmpty();
        }

        @Test
        @DisplayName("something absurdly long is refused rather than overflowing")
        void refusesTheAbsurd() {
            assertThat(Times.parse("99999999999999999999d"))
                    .as("an overflow here is a ban that ends in the past")
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------ for ever

    @Nested
    @DisplayName("for ever")
    class ForEver {

        @Test
        @DisplayName("the words people use for it are recognised")
        void recognisesIt() {
            assertThat(Times.isForEver("perm")).isTrue();
            assertThat(Times.isForEver("permanent")).isTrue();
            assertThat(Times.isForEver("forever")).isTrue();
            assertThat(Times.isForEver("never")).isTrue();
            assertThat(Times.isForEver("2h")).isFalse();
        }

        @Test
        @DisplayName("it is told apart from something that could not be read")
        void isNotTheSameAsRubbish() {
            assertThat(Times.parse("perm")).isEmpty();
            assertThat(Times.isForEver("perm")).isTrue();
            assertThat(Times.isForEver("qwerty"))
                    .as("a command that treated an unreadable length as 'for ever' would be a "
                            + "typo that permanently bans somebody")
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------ writing it back

    @Nested
    @DisplayName("saying it back")
    class Describing {

        @Test
        @DisplayName("it reads the way somebody would say it")
        void inWords() {
            assertThat(Times.describe(Duration.ofSeconds(90))).isEqualTo("1 minute, 30 seconds");
            assertThat(Times.describe(Duration.ofHours(25))).isEqualTo("1 day, 1 hour");
            assertThat(Times.describe(Duration.ofSeconds(1))).isEqualTo("1 second");
        }

        @Test
        @DisplayName("it stops at the two biggest parts, because nobody reads past them")
        void isNotExhaustive() {
            assertThat(Times.describe(Duration.ofDays(400).plusHours(3).plusMinutes(7)))
                    .as("'1 year, 1 month, 4 days, 3 hours, 7 minutes' is a wall, not an answer")
                    .doesNotContain("minute");
        }

        @Test
        @DisplayName("nothing at all has a word of its own")
        void nothingAtAll() {
            assertThat(Times.describe(Duration.ZERO)).isEqualTo("no time at all");
            assertThat(Times.describe(null)).isEqualTo("for ever");
        }

        @Test
        @DisplayName("short forms for somewhere there is no room")
        void shortForm() {
            assertThat(Times.brief(Duration.ofSeconds(90))).isEqualTo("1m 30s");
            assertThat(Times.brief(Duration.ofHours(2))).isEqualTo("2h");
            assertThat(Times.brief(null)).isEqualTo("∞");
        }

        @Test
        @DisplayName("what it writes, it can read again")
        void roundTrips() {
            for (Duration original : new Duration[]{
                    Duration.ofSeconds(45), Duration.ofMinutes(90), Duration.ofHours(36),
                    Duration.ofDays(10)}) {
                assertThat(Times.parse(Times.brief(original)))
                        .as("otherwise a length shown in a menu cannot be typed back into a command")
                        .contains(original);
            }
        }
    }
}
