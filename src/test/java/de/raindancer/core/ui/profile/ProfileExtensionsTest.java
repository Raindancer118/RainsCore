package de.raindancer.core.ui.profile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileExtensionsTest {

    @AfterEach
    void clearWhateverThisTestRegistered() {
        ProfileExtensions.all().forEach(ProfileExtensions::unregister);
    }

    @Test
    @DisplayName("a registered extension is in all(), in the order it registered")
    void registersInOrder() {
        ProfileExtension first = (viewer, subject, parent) -> null;
        ProfileExtension second = (viewer, subject, parent) -> null;

        ProfileExtensions.register(first);
        ProfileExtensions.register(second);

        assertThat(ProfileExtensions.all()).containsExactly(first, second);
    }

    @Test
    @DisplayName("an unregistered extension is gone")
    void unregisterRemovesIt() {
        ProfileExtension extension = (viewer, subject, parent) -> null;
        ProfileExtensions.register(extension);

        ProfileExtensions.unregister(extension);

        assertThat(ProfileExtensions.all()).isEmpty();
    }

    @Test
    @DisplayName("null is quietly ignored, both ways")
    void nullIsIgnored() {
        ProfileExtensions.register(null);
        ProfileExtensions.unregister(null);

        assertThat(ProfileExtensions.all()).isEmpty();
    }
}
