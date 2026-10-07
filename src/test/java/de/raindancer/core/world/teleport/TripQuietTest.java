package de.raindancer.core.world.teleport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TripQuietTest {

    @Test
    @DisplayName("a trip makes its sounds unless asked not to, and every other change keeps that choice")
    void quietSurvivesTheOtherChanges() {
        assertThat(Trip.to("home").isQuiet()).isFalse();
        Trip quiet = Trip.to("home").quiet().after(3).exactly().searching(4).bringing(Companions.NOBODY);
        assertThat(quiet.isQuiet()).isTrue();
        assertThat(quiet.warmupSeconds()).isEqualTo(3);
    }

    @Test
    @DisplayName("the five-argument constructor older plugins were built against still works, and is not quiet")
    void oldShape() {
        assertThat(new Trip("spawn", 2, true, 8, Companions.NOBODY).isQuiet()).isFalse();
    }
}
