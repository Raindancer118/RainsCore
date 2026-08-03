package de.raindancer.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An outside plugin still compiles against this library.
 *
 * <h2>Why this is a test and not a note in a readme</h2>
 * Rain's Core is a utility library: the whole point of it is that other projects — including
 * {@code TheHungerGames}, which is a separate repository on a different build system — are written
 * against its API. That makes every public signature here a promise, and the ordinary way to
 * discover a broken promise is that somebody else's build fails, days later, with an error naming a
 * class they did not touch.
 *
 * <p>So {@code examples/DemoPlugin.java.txt} is compiled here, against this build's own classes and
 * the same Paper API a real plugin would use. It exercises the parts a plugin actually reaches for —
 * a settings record, chat, an item with an ability and a recipe, an achievement, a saved place, an
 * action-bar countdown, the startup banner. Rename a method any of those uses and this fails, in the
 * project that caused it.
 *
 * <p>It is kept as {@code .txt} so it is not compiled twice: once by the build as ordinary test
 * source, which would prove nothing about an outside project's classpath, and once here.
 */
class ConsumerCompilesTest {

    private static final Path EXAMPLE = Path.of("examples/DemoPlugin.java.txt");

    @Test
    @DisplayName("the example plugin compiles against this library, as an outside project would")
    void theExampleStillCompiles(@TempDir Path output) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler)
                .as("this test needs a JDK rather than a JRE, which the build already requires")
                .isNotNull();

        Path source = output.resolve("DemoPlugin.java");
        Files.writeString(source, Files.readString(EXAMPLE));

        DiagnosticCollector<JavaFileObject> problems = new DiagnosticCollector<>();
        List<String> options = new ArrayList<>(List.of(
                "-classpath", classpath(),
                "-d", output.toString(),
                // Same release the library and every plugin using it are built at.
                "--release", "25",
                // The example is a plugin, not a library: unchecked warnings in it are not this
                // project's business, and turning them into noise would teach everyone to ignore
                // the output of this test.
                "-nowarn"));

        try (StandardJavaFileManager files = compiler.getStandardFileManager(problems, null, null)) {
            boolean compiled = compiler.getTask(null, files, problems, options, null,
                    files.getJavaFileObjectsFromPaths(List.of(source))).call();

            List<String> errors = problems.getDiagnostics().stream()
                    .filter(problem -> problem.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                    .map(problem -> problem.getLineNumber() + ": " + problem.getMessage(null))
                    .toList();

            assertThat(errors)
                    .as("examples/DemoPlugin.java.txt no longer compiles, so this change breaks "
                            + "every plugin written against this library")
                    .isEmpty();
            assertThat(compiled).isTrue();
        }
    }

    @Test
    @DisplayName("the example reaches for the things a plugin actually uses")
    void theExampleIsRepresentative() throws IOException {
        String source = Files.readString(EXAMPLE);
        // A compile check is only worth what it covers. If somebody trims the example down, this
        // says so rather than letting the coverage quietly evaporate.
        assertThat(source)
                .contains("RainsCore.get()")
                .contains("settingsFor(")
                .contains("chatFor(")
                .contains("itemAbilities()")
                .contains("items()")
                .contains("achievements()")
                .contains("places()")
                .contains("actionBars()")
                .contains("Banner.of(");
    }

    /**
     * This build's own classes, plus everything it compiles against.
     *
     * <p>{@code target/classes} rather than the installed jar on purpose: the point is to catch a
     * break in the working tree, now, and testing against the last thing that was installed would
     * pass on exactly the change that breaks somebody.
     */
    private static String classpath() {
        // The whole test classpath, not a hand-picked list of jar names. Picking was the first
        // attempt and it failed for a reason worth keeping: paper-api's own annotations reference
        // Guava, so leaving Guava out produced an error about @NotNull on Material rather than
        // anything to do with the example. A plugin compiling against Paper has all of this anyway.
        return "target/classes" + java.io.File.pathSeparator
                + System.getProperty("java.class.path");
    }

    @Test
    @DisplayName("the instructions for depending on it exist and say provided, not shaded")
    void theInstructionsAreThere() throws IOException {
        String readme = Files.readString(Path.of("examples/README.md"));
        assertThat(readme)
                .as("shading this library would give every plugin its own action bar manager, "
                        + "its own item registry and its own scoreboard owner, none of which would "
                        + "know about each other — which is the problem it exists to remove")
                .contains("provided")
                .contains("compileOnly");
    }

    /**
     * The instructions said {@code depend: [RainsCore]}, which is the legacy {@code plugin.yml}
     * syntax and is silently ignored in a {@code paper-plugin.yml}. A plugin following them loaded
     * with no access to these classes and died on {@code NoClassDefFoundError} naming a class its
     * author had never written. Found by booting a real server, which is the only thing that would
     * have found it.
     */
    @Test
    @DisplayName("the instructions give the paper-plugin dependency block, not the legacy one")
    void theInstructionsUseTheRightDependencySyntax() throws IOException {
        String readme = Files.readString(Path.of("examples/README.md"));
        assertThat(readme)
                .as("without join-classpath a paper plugin cannot see this library's classes at all")
                .contains("join-classpath: true")
                .contains("load: BEFORE");
        assertThat(readme)
                .as("the legacy form must stay in the readme, but only as the thing not to write")
                .contains("legacy plugin.yml only");
    }
}
