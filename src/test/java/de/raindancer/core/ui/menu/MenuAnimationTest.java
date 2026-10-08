package de.raindancer.core.ui.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MenuAnimationTest {

    @Test
    @DisplayName("frames slow down from the first gap to the last, like a wheel running out of spin")
    void slowingDown() {
        List<Integer> at = MenuAnimation.schedule(5, 1, 9);
        assertThat(at).containsExactly(1, 4, 9, 16, 25);
    }

    @Test
    @DisplayName("an even pace is one gap throughout")
    void evenPace() {
        assertThat(MenuAnimation.schedule(4, 2, 2)).containsExactly(2, 4, 6, 8);
    }

    @Test
    @DisplayName("nonsense asks for at least one frame, one tick apart")
    void bounds() {
        assertThat(MenuAnimation.schedule(0, 0, -5)).containsExactly(1);
        assertThat(MenuAnimation.schedule(3, 0, 0)).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("frames are run in order, each exactly once, and the end exactly once")
    void stepping() {
        StringBuilder seen = new StringBuilder();
        MenuAnimation.Run run = new MenuAnimation.Run(MenuAnimation.schedule(3, 1, 1),
                frame -> seen.append(frame), () -> seen.append("!"));
        for (int tick = 0; tick < 10; tick++) {
            run.tick(true);
        }
        assertThat(seen.toString()).isEqualTo("012!");
        assertThat(run.finished()).isTrue();
    }

    @Test
    @DisplayName("closing the window skips the remaining frames but still ends, so the result is never lost")
    void closedEarly() {
        StringBuilder seen = new StringBuilder();
        MenuAnimation.Run run = new MenuAnimation.Run(MenuAnimation.schedule(5, 1, 1),
                frame -> seen.append(frame), () -> seen.append("!"));
        run.tick(true);
        run.tick(false);
        run.tick(false);
        assertThat(seen.toString()).isEqualTo("0!");
        assertThat(run.finished()).isTrue();
    }
}
