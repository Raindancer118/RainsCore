package de.raindancer.core.platform.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A moderator typing a silly length gets "not a length", not an exception out of the command. */
class TimesParsingTest {

    @Test
    @DisplayName("a number too long for a long is unreadable, not a crash")
    void hugeNumbers() {
        assertThat(Times.parseLenient("99999999999999999999")).isEmpty();
        assertThat(Times.parseLenient("99999999999999999999d")).isEmpty();
        assertThatThrownBy(() -> Times.parseStrict("99999999999999999999d"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a length too long for a Duration is unreadable, not a crash")
    void overflowingLengths() {
        assertThat(Times.parseLenient("999999999999999999w")).isEmpty();
        assertThat(Times.parseLenient("9223372036854775807d9223372036854775807d")).isEmpty();
        assertThatThrownBy(() -> Times.parseStrict("999999999999999999w"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ordinary lengths still read")
    void ordinary() {
        assertThat(Times.parseLenient("2h30m")).hasValueSatisfying(d -> assertThat(d.toMinutes()).isEqualTo(150));
        assertThat(Times.parseStrict("7d").toDays()).isEqualTo(7);
    }
}
