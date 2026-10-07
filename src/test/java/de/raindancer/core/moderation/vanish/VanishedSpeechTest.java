package de.raindancer.core.moderation.vanish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Whether a vanished player's line goes out, is held for a "send anyway", or was already confirmed. */
class VanishedSpeechTest {

    private static final UUID MOD = UUID.nameUUIDFromBytes("mod".getBytes());
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final VanishedSpeech speech = new VanishedSpeech(now::get);

    @Test
    @DisplayName("a vanished player's line is held; a visible player's never is")
    void held() {
        assertThat(speech.shouldHold(MOD, true, "hello")).isTrue();
        assertThat(speech.shouldHold(MOD, false, "hello")).isFalse();
    }

    @Test
    @DisplayName("send anyway lets exactly that line through, once")
    void once() {
        speech.allowOnce(MOD, "hello");

        assertThat(speech.shouldHold(MOD, true, "hello")).isFalse();
        assertThat(speech.shouldHold(MOD, true, "hello")).as("used up").isTrue();
    }

    @Test
    @DisplayName("a different line is still held")
    void onlyThatLine() {
        speech.allowOnce(MOD, "hello");

        assertThat(speech.shouldHold(MOD, true, "something else")).isTrue();
    }

    @Test
    @DisplayName("a confirmation not used within two minutes lapses")
    void lapses() {
        speech.allowOnce(MOD, "hello");
        now.addAndGet(VanishedSpeech.ALLOW_FOR.toMillis() + 1);

        assertThat(speech.shouldHold(MOD, true, "hello")).isTrue();
    }

    @Test
    @DisplayName("stop asking holds until they reappear, then asks again next time they vanish")
    void stopAsking() {
        speech.stopAsking(MOD);
        assertThat(speech.shouldHold(MOD, true, "a")).isFalse();
        assertThat(speech.shouldHold(MOD, true, "b")).isFalse();

        assertThat(speech.shouldHold(MOD, false, "visible again")).isFalse();
        assertThat(speech.shouldHold(MOD, true, "vanished again")).isTrue();
    }

    @Test
    @DisplayName("messaging commands are recognised however they are written; others are not")
    void commands() {
        assertThat(VanishedSpeech.speaking("msg Lilly hi")).contains("msg");
        assertThat(VanishedSpeech.speaking("/essentials:tell Lilly hi")).contains("tell");
        assertThat(VanishedSpeech.speaking("R thanks")).contains("r");
        assertThat(VanishedSpeech.speaking("me waves")).contains("me");
        assertThat(VanishedSpeech.speaking("msg")).as("nothing said yet").isEmpty();
        assertThat(VanishedSpeech.speaking("home base")).isEqualTo(Optional.empty());
    }
}
