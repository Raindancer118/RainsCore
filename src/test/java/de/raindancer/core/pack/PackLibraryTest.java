package de.raindancer.core.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when several plugins each want assets on a player's screen.
 *
 * <p>This is the collision the whole package exists for. A player has one resource pack applied at a
 * time in any meaningful sense, so the moment the claims module wants menu icons and a record seller
 * wants custom discs, somebody has to decide what the player actually gets. Left alone, the answer is
 * "whichever plugin sent last", and the other one's assets vanish with nothing logged.
 */
@DisplayName("the pack library")
class PackLibraryTest {

    @TempDir
    Path directory;

    private Path pack(String name) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, "not really a zip, but it is a file that exists");
        return file;
    }

    // ------------------------------------------------------------------ holding contributions

    @Nested
    @DisplayName("taking contributions")
    class Taking {

        @Test
        @DisplayName("a contribution can be offered and is then listed")
        void takesOne() throws IOException {
            PackLibrary library = new PackLibrary();
            assertThat(library.offer(PackContribution.of("Claims", "icons", pack("icons.zip"))))
                    .isTrue();

            assertThat(library.all()).hasSize(1);
            assertThat(library.byId("claims:icons")).isPresent();
        }

        @Test
        @DisplayName("offering the same id again replaces it rather than sending both")
        void replacesItsOwn() throws IOException {
            PackLibrary library = new PackLibrary();
            library.offer(PackContribution.of("Claims", "icons", pack("old.zip")));
            library.offer(PackContribution.of("Claims", "icons", pack("new.zip")));

            assertThat(library.all()).hasSize(1);
            assertThat(library.byId("claims:icons"))
                    .get()
                    .extracting(contribution -> contribution.source().getFileName().toString())
                    .isEqualTo("new.zip");
        }

        @Test
        @DisplayName("a contribution whose file is not there is refused, with a reason")
        void refusesWhatIsNotThere() {
            PackLibrary library = new PackLibrary();
            PackContribution missing =
                    PackContribution.of("Claims", "icons", directory.resolve("nope.zip"));

            assertThat(library.offer(missing))
                    .as("a pack that cannot be read must be refused now, not at build time when "
                            + "somebody is waiting for it")
                    .isFalse();
            assertThat(library.all()).isEmpty();
            assertThat(library.problems()).isNotEmpty();
        }

        @Test
        @DisplayName("a plugin being disabled takes its contributions with it")
        void withdrawsByOwner() throws IOException {
            PackLibrary library = new PackLibrary();
            library.offer(PackContribution.of("Claims", "icons", pack("a.zip")));
            library.offer(PackContribution.of("Claims", "fonts", pack("b.zip")));
            library.offer(PackContribution.of("Records", "discs", pack("c.zip")));

            assertThat(library.withdrawAllFrom("Claims")).isEqualTo(2);
            assertThat(library.all())
                    .extracting(PackContribution::owner)
                    .containsExactly("Records");
        }

        @Test
        @DisplayName("withdrawing something nobody offered is not an error")
        void withdrawingNothing() {
            PackLibrary library = new PackLibrary();
            assertThat(library.withdraw("claims:icons")).isFalse();
            assertThat(library.withdrawAllFrom("Claims")).isZero();
        }
    }

    // ------------------------------------------------------------------ deciding the order

    /**
     * Order is not cosmetic here. The merger lets the last pack win a conflict, and the zip it
     * writes is only reproducible — and so only cached by clients rather than redownloaded — if the
     * same set of contributions always produces the same order.
     */
    @Nested
    @DisplayName("the order they are applied in")
    class Ordering {

        @Test
        @DisplayName("higher priority is applied later, so it wins a conflict")
        void priorityDecides() throws IOException {
            PackLibrary library = new PackLibrary();
            library.offer(PackContribution.of("Records", "discs", pack("a.zip")).priority(10));
            library.offer(PackContribution.of("Claims", "icons", pack("b.zip")).priority(1));

            assertThat(library.all())
                    .extracting(PackContribution::id)
                    .containsExactly("claims:icons", "records:discs");
        }

        @Test
        @DisplayName("the same priority is broken by id, not by who asked first")
        void tiesAreStable() throws IOException {
            PackLibrary first = new PackLibrary();
            first.offer(PackContribution.of("Records", "discs", pack("a.zip")));
            first.offer(PackContribution.of("Claims", "icons", pack("b.zip")));

            PackLibrary second = new PackLibrary();
            second.offer(PackContribution.of("Claims", "icons", pack("b.zip")));
            second.offer(PackContribution.of("Records", "discs", pack("a.zip")));

            assertThat(first.all().stream().map(PackContribution::id).toList())
                    .as("two servers with the same plugins in a different load order must build "
                            + "the same pack, or every client redownloads it for nothing")
                    .isEqualTo(second.all().stream().map(PackContribution::id).toList());
        }

        @Test
        @DisplayName("the sources come out in the order they are applied")
        void sourcesFollowTheOrder() throws IOException {
            PackLibrary library = new PackLibrary();
            library.offer(PackContribution.of("Records", "discs", pack("a.zip")).priority(10));
            library.offer(PackContribution.of("Claims", "icons", pack("b.zip")).priority(1));

            List<Path> sources = library.sources();
            assertThat(sources).hasSize(2);
            assertThat(sources.get(0).getFileName().toString()).isEqualTo("b.zip");
        }
    }

    // ------------------------------------------------------------------ what a contribution is

    @Nested
    @DisplayName("a contribution")
    class Contributions {

        @Test
        @DisplayName("its id is its owner and its name, lowercased")
        void idIsOwnerAndName() throws IOException {
            PackContribution contribution = PackContribution.of("Claims", "Menu Icons", pack("a.zip"));
            assertThat(contribution.id()).isEqualTo("claims:menu icons");
        }

        @Test
        @DisplayName("an owner or name that is blank is refused when it is made")
        void refusesBlanks() throws IOException {
            Path file = pack("a.zip");
            assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> PackContribution.of("", "icons", file))).isNotNull();
            assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> PackContribution.of("Claims", " ", file))).isNotNull();
        }

        @Test
        @DisplayName("it says what it is for, so a conflict names something a human recognises")
        void describesItself() throws IOException {
            PackContribution contribution = PackContribution.of("Claims", "icons", pack("a.zip"))
                    .describedAs("The icons the claim menu uses");
            assertThat(contribution.description()).isEqualTo("The icons the claim menu uses");
            assertThat(PackContribution.of("Claims", "icons", pack("b.zip")).description())
                    .as("no description is an empty one, never null")
                    .isEmpty();
        }
    }
}
