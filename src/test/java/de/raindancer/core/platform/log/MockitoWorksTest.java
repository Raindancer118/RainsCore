package de.raindancer.core.platform.log;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * That Mockito actually works in this project, on the two shapes Core needs it for.
 *
 * <p>Not a test of Core's own code, and it stays anyway: both of these have a way of breaking on a JDK
 * upgrade rather than on a code change, and the failure looks like the test that happened to use them being
 * wrong. A test whose whole subject is "the tool works" fails in a way that names the tool.
 *
 * <ul>
 *   <li><b>A Bukkit interface.</b> {@code Player} has no implementation on the test classpath, which is why
 *       everything that speaks to one was previously untestable.</li>
 *   <li><b>A final class with a package-private constructor.</b> {@link LogChannel} is exactly that, and it
 *       only works through the inline mock maker — which is also the part that needs Byte Buddy's
 *       experimental flag on Java 25. See the surefire {@code argLine} in the pom.</li>
 * </ul>
 */
class MockitoWorksTest {

    @Test
    @DisplayName("a Bukkit interface can be mocked")
    void bukkitTypes() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Raindancer118");

        assertThat(player.getName()).isEqualTo("Raindancer118");
    }

    @Test
    @DisplayName("a final class with a package-private constructor can be mocked, and its calls recorded")
    void finalClasses() {
        List<String> logged = new java.util.ArrayList<>();
        LogChannel log = mock(LogChannel.class, call -> {
            if (call.getArguments().length > 0 && call.getArgument(0) instanceof String line) {
                logged.add(line);
            }
            return null;
        });

        log.warn("something happened to {}", "the border");

        // Through a default answer rather than a stubbed varargs method: a matcher for Object... silently
        // fails to bind the array, and an assertion about what was logged then checks an empty list.
        assertThat(logged).containsExactly("something happened to {}");
    }
}
