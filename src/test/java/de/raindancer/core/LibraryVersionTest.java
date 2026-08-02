package de.raindancer.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A library's version is written down twice, so something has to keep the two honest.
 *
 * <h2>Why it is written twice at all</h2>
 * The POM needs it to compile against; {@code paper-plugin.yml} needs it so Paper can fetch the same
 * jar at runtime. There is no way to derive one from the other — resource filtering could inject a
 * property into the yml, but then the yml no longer says what it depends on, which is the one place
 * a server owner looks.
 *
 * <p>So: two declarations and a test. If they drift, the plugin compiles against one version and
 * runs against another, which fails as a {@code NoSuchMethodError} the first time a scoreboard is
 * shown — a long way from the line that caused it.
 */
class LibraryVersionTest {

    private static final Path POM = Path.of("pom.xml");
    private static final Path DESCRIPTOR = Path.of("src/main/resources/paper-plugin.yml");

    @Test
    @DisplayName("every library Paper fetches is the version the build compiled against")
    void runtimeLibrariesMatchTheBuild() throws IOException {
        String pom = Files.readString(POM);
        List<String> mismatches = new ArrayList<>();

        for (String coordinate : declaredLibraries()) {
            String[] parts = coordinate.split(":");
            assertThat(parts)
                    .as("'%s' in paper-plugin.yml is not group:artifact:version", coordinate)
                    .hasSize(3);
            String artifact = parts[1];
            String declared = parts[2];

            String inPom = versionOf(pom, artifact);
            if (inPom == null) {
                mismatches.add(artifact + " is fetched at runtime but the build never compiles "
                        + "against it — nothing checks that the API it uses exists");
            } else if (!inPom.equals(declared)) {
                mismatches.add(artifact + ": the build uses " + inPom
                        + " and Paper would fetch " + declared);
            }
        }

        assertThat(mismatches)
                .as("compiling against one version and running against another fails as a "
                        + "NoSuchMethodError a long way from the cause")
                .isEmpty();
    }

    /** The coordinates under {@code libraries:} in the descriptor. */
    private static List<String> declaredLibraries() throws IOException {
        List<String> found = new ArrayList<>();
        boolean inBlock = false;
        for (String line : Files.readAllLines(DESCRIPTOR)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("libraries:")) {
                inBlock = true;
                continue;
            }
            if (inBlock) {
                if (trimmed.startsWith("- ")) {
                    found.add(trimmed.substring(2).trim());
                } else if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    break;
                }
            }
        }
        return found;
    }

    /**
     * The version the POM resolves for an artifact, following one level of property.
     *
     * <p>One level is enough for how this POM is written and keeps the test from becoming a second
     * Maven; anything deeper would be worth simplifying in the POM instead.
     */
    private static String versionOf(String pom, String artifact) {
        Matcher dependency = Pattern.compile(
                "<artifactId>" + Pattern.quote(artifact) + "</artifactId>\\s*"
                        + "<version>([^<]+)</version>").matcher(pom);
        if (!dependency.find()) {
            return null;
        }
        String version = dependency.group(1).trim();
        Matcher property = Pattern.compile("\\$\\{([^}]+)}").matcher(version);
        if (!property.matches()) {
            return version;
        }
        Matcher defined = Pattern.compile(
                "<" + Pattern.quote(property.group(1)) + ">([^<]+)</").matcher(pom);
        return defined.find() ? defined.group(1).trim() : null;
    }

    @Test
    @DisplayName("the descriptor declares the libraries the plugin actually needs at runtime")
    void declaresWhatItNeeds() throws IOException {
        assertThat(declaredLibraries())
                .as("FastBoard is compiled against but not shaded, so Paper has to fetch it")
                .anyMatch(coordinate -> coordinate.startsWith("fr.mrmicky:fastboard:"));
    }
}
