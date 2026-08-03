package de.raindancer.core.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning what the plugins offered into the one file a client downloads.
 *
 * <p>The part worth testing hardest is that the same contributions always produce the same bytes.
 * A resource pack is cached by its hash, so a build that is not reproducible means every client
 * downloads tens of megabytes again on every restart — which looks like a slow server rather than
 * like a bug, and so goes unfixed for years.
 */
@DisplayName("building the pack")
class PackBuilderTest {

    @TempDir
    Path directory;

    private Path work() {
        return directory.resolve("work");
    }

    /** A zip with a pack.mcmeta and whatever else is asked for — the least a real pack has. */
    private Path zip(String name, int format, Map<String, String> files) throws IOException {
        Path file = directory.resolve(name);
        Map<String, String> all = new LinkedHashMap<>();
        all.put("pack.mcmeta", "{\"pack\":{\"description\":\"x\",\"pack_format\":" + format + "}}");
        all.putAll(files);
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : all.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private Path zip(String name, Map<String, String> files) throws IOException {
        return zip(name, 46, files);
    }

    private PackLibrary libraryOf(PackContribution... contributions) {
        PackLibrary library = new PackLibrary();
        for (PackContribution contribution : contributions) {
            library.offer(contribution);
        }
        return library;
    }

    private static java.util.List<String> namesIn(Path zipFile) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            return zip.stream().map(ZipEntry::getName).sorted().toList();
        }
    }

    private static String textIn(Path zipFile, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry found = zip.getEntry(entry);
            return found == null ? null
                    : new String(zip.getInputStream(found).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ nothing to do

    @Test
    @DisplayName("nothing offered builds nothing, rather than an empty pack")
    void nothingToBuild() {
        PackBuilder builder = new PackBuilder(work());
        assertThat(builder.build(new PackLibrary(), "Server pack")).isEmpty();
    }

    // ------------------------------------------------------------------ one contribution

    @Nested
    @DisplayName("with one contribution")
    class Single {

        @Test
        @DisplayName("a single zip keeps its own bytes rather than being merged with nothing")
        void oneZipIsNotRewritten() throws IOException {
            Path only = zip("only.zip", Map.of("assets/x.txt", "hello"));
            PackBuilder builder = new PackBuilder(work());

            Optional<PackBuild> built = builder.build(libraryOf(
                    PackContribution.of("Claims", "icons", only)), "Server pack");

            assertThat(built).isPresent();
            assertThat(Files.readAllBytes(built.get().parts().get(0).file()))
                    .as("merging one pack with nothing would rewrite it for no reason, changing "
                            + "its hash and making every client download it again")
                    .isEqualTo(Files.readAllBytes(only));
            assertThat(built.get().contributions()).isEqualTo(1);
            assertThat(built.get().conflicts()).isEmpty();
            assertThat(built.get().digest()).hasSize(40);
        }

        @Test
        @DisplayName("even a single pack ends up somewhere the server can serve it")
        void oneZipIsStillInTheWorkFolder() throws IOException {
            Path only = zip("only.zip", Map.of("assets/x.txt", "hello"));

            PackBuild built = new PackBuilder(work()).build(libraryOf(
                    PackContribution.of("Claims", "icons", only)), "Server pack").orElseThrow();

            assertThat(built.parts().get(0).file().getParent())
                    .as("the web server serves one folder; a build pointing into some other "
                            + "plugin's data folder is a 404 for every client, which is exactly "
                            + "what a live server found here")
                    .isEqualTo(work());
        }

        @Test
        @DisplayName("a folder is zipped up")
        void oneFolderIsZipped() throws IOException {
            Path folder = directory.resolve("loose");
            Files.createDirectories(folder.resolve("assets/minecraft"));
            Files.writeString(folder.resolve("pack.mcmeta"),
                    "{\"pack\":{\"description\":\"x\",\"pack_format\":46}}");
            Files.writeString(folder.resolve("assets/minecraft/hello.txt"), "hi");

            Optional<PackBuild> built = new PackBuilder(work()).build(libraryOf(
                    PackContribution.of("Claims", "icons", folder)), "Server pack");

            assertThat(built).isPresent();
            assertThat(namesIn(built.get().parts().get(0).file()))
                    .containsExactly("assets/minecraft/hello.txt", "pack.mcmeta");
        }

        @Test
        @DisplayName("the same folder builds the same bytes twice running")
        void zippingAFolderIsReproducible() throws IOException {
            Path folder = directory.resolve("loose");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("pack.mcmeta"),
                    "{\"pack\":{\"description\":\"x\",\"pack_format\":46}}");

            PackLibrary library = libraryOf(PackContribution.of("Claims", "icons", folder));
            String first = new PackBuilder(work()).build(library, "Server pack")
                    .orElseThrow().digest();
            String second = new PackBuilder(directory.resolve("work2"))
                    .build(library, "Server pack").orElseThrow().digest();

            assertThat(second)
                    .as("a pack is cached by its hash; a build that is not reproducible means "
                            + "every client redownloads it on every restart")
                    .isEqualTo(first);
        }
    }

    // ------------------------------------------------------------------ several

    @Nested
    @DisplayName("with several contributions")
    class Several {

        private PackBuilder combining() {
            PackBuilder builder = new PackBuilder(work());
            builder.mode(PackMode.COMBINED);
            return builder;
        }

        @Test
        @DisplayName("stacked, each one is sent as its own pack")
        void stacksByDefault() throws IOException {
            Path first = zip("a.zip", Map.of("assets/a.txt", "from a"));
            Path second = zip("b.zip", Map.of("assets/b.txt", "from b"));

            PackBuild built = new PackBuilder(work()).build(libraryOf(
                    PackContribution.of("Claims", "icons", first).priority(1),
                    PackContribution.of("Records", "discs", second).priority(2)),
                    "Server pack").orElseThrow();

            assertThat(built.mode()).isEqualTo(PackMode.STACKED);
            assertThat(built.parts()).hasSize(2);
            assertThat(built.parts()).extracting(PackPart::label)
                    .as("applied in the library's order, so the last one still wins on the client")
                    .containsExactly("claims:icons", "records:discs");
        }

        @Test
        @DisplayName("stacked, each pack keeps its own bytes so a client can keep the ones it has")
        void stackedPartsAreUntouched() throws IOException {
            Path first = zip("a.zip", Map.of("assets/a.txt", "from a"));
            Path second = zip("b.zip", Map.of("assets/b.txt", "from b"));

            PackBuild built = new PackBuilder(work()).build(libraryOf(
                    PackContribution.of("Claims", "icons", first),
                    PackContribution.of("Records", "discs", second)), "Server pack").orElseThrow();

            assertThat(Files.readAllBytes(built.parts().get(0).file()))
                    .as("adding a plugin must cost players that plugin's download, not all of them")
                    .isEqualTo(Files.readAllBytes(first));
        }

        @Test
        @DisplayName("stacked, adding one changes what the whole set is identified by")
        void addingOneChangesTheDigest() throws IOException {
            Path first = zip("a.zip", Map.of("assets/a.txt", "from a"));
            Path second = zip("b.zip", Map.of("assets/b.txt", "from b"));

            String one = new PackBuilder(work()).build(libraryOf(
                    PackContribution.of("Claims", "icons", first)), "Server pack")
                    .orElseThrow().digest();
            String two = new PackBuilder(directory.resolve("w2")).build(libraryOf(
                    PackContribution.of("Claims", "icons", first),
                    PackContribution.of("Records", "discs", second)), "Server pack")
                    .orElseThrow().digest();

            assertThat(two)
                    .as("otherwise a player sent the first pack is never offered the second")
                    .isNotEqualTo(one);
        }

        @Test
        @DisplayName("stacked, packs for different game versions are fine — the client sorts it out")
        void stackedDoesNotCareAboutFormats() throws IOException {
            Path old = zip("old.zip", 9, Map.of("assets/a.txt", "old"));
            Path recent = zip("new.zip", 46, Map.of("assets/b.txt", "new"));

            assertThat(new PackBuilder(work()).build(libraryOf(
                    PackContribution.of("Claims", "icons", old),
                    PackContribution.of("Records", "discs", recent)), "Server pack"))
                    .as("this is the case combining cannot do at all, and is a real reason to "
                            + "prefer stacking")
                    .isPresent();
        }

        @Test
        @DisplayName("stacked, something that is not a pack is left out rather than served")
        void stackedDropsWhatIsNotAPack() throws IOException {
            Path rubbish = directory.resolve("not-a-pack.zip");
            Files.writeString(rubbish, "I am not a zip file.");
            Path fine = zip("fine.zip", Map.of("assets/a.txt", "a"));

            PackBuilder builder = new PackBuilder(work());
            PackBuild built = builder.build(libraryOf(
                    PackContribution.of("Claims", "icons", rubbish),
                    PackContribution.of("Records", "discs", fine)), "Server pack").orElseThrow();

            assertThat(built.parts())
                    .as("one plugin shipping something broken must not cost everybody else theirs")
                    .hasSize(1);
            assertThat(builder.problems())
                    .as("and it has to be said, or the first anyone knows is missing textures")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("combined, they are merged into one file")
        void combines() throws IOException {
            Path first = zip("a.zip", Map.of("assets/a.txt", "from a"));
            Path second = zip("b.zip", Map.of("assets/b.txt", "from b"));

            Optional<PackBuild> built = combining().build(libraryOf(
                    PackContribution.of("Claims", "icons", first),
                    PackContribution.of("Records", "discs", second)), "Server pack");

            assertThat(built).isPresent();
            assertThat(built.get().parts()).hasSize(1);
            assertThat(namesIn(built.get().parts().get(0).file()))
                    .contains("assets/a.txt", "assets/b.txt", "pack.mcmeta");
            assertThat(built.get().contributions()).isEqualTo(2);
        }

        @Test
        @DisplayName("combined, the same file from two plugins is reported rather than silently taken")
        void reportsConflicts() throws IOException {
            Path first = zip("a.zip", Map.of("assets/same.txt", "from a"));
            Path second = zip("b.zip", Map.of("assets/same.txt", "from b"));

            PackBuild built = combining().build(libraryOf(
                    PackContribution.of("Claims", "icons", first).priority(1),
                    PackContribution.of("Records", "discs", second).priority(2)),
                    "Server pack").orElseThrow();

            assertThat(built.conflicts())
                    .as("the loser's texture is simply gone; somebody has to be told")
                    .isNotEmpty();
            assertThat(textIn(built.parts().get(0).file(), "assets/same.txt"))
                    .as("the higher priority is applied last and so wins")
                    .isEqualTo("from b");
        }

        @Test
        @DisplayName("combined, the same contributions build the same bytes twice running")
        void isReproducible() throws IOException {
            Path first = zip("a.zip", Map.of("assets/a.txt", "from a"));
            Path second = zip("b.zip", Map.of("assets/b.txt", "from b"));
            PackLibrary library = libraryOf(
                    PackContribution.of("Claims", "icons", first),
                    PackContribution.of("Records", "discs", second));

            String one = combining().build(library, "Server pack").orElseThrow().digest();
            PackBuilder other = new PackBuilder(directory.resolve("work2"));
            other.mode(PackMode.COMBINED);
            String two = other.build(library, "Server pack").orElseThrow().digest();

            assertThat(two).isEqualTo(one);
        }

        @Test
        @DisplayName("combined, versions that do not overlap fail with a reason, not a broken zip")
        void refusesIncompatibleFormats() throws IOException {
            Path old = zip("old.zip", 9, Map.of("assets/a.txt", "old"));
            Path recent = zip("new.zip", 46, Map.of("assets/b.txt", "new"));

            PackBuilder builder = combining();
            Optional<PackBuild> built = builder.build(libraryOf(
                    PackContribution.of("Claims", "icons", old),
                    PackContribution.of("Records", "discs", recent)), "Server pack");

            assertThat(built).isEmpty();
            assertThat(builder.problems())
                    .as("a pack that cannot be built must say why; a silent empty is the same "
                            + "symptom as no pack configured at all")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("combined, a source that is not a zip at all fails with a reason")
        void survivesRubbish() throws IOException {
            Path rubbish = directory.resolve("not-a-pack.zip");
            Files.writeString(rubbish, "I am not a zip file.");
            Path fine = zip("fine.zip", Map.of("assets/a.txt", "a"));

            PackBuilder builder = combining();
            assertThat(builder.build(libraryOf(
                    PackContribution.of("Claims", "icons", rubbish),
                    PackContribution.of("Records", "discs", fine)), "Server pack")).isEmpty();
            assertThat(builder.problems()).isNotEmpty();
        }
    }

    // ------------------------------------------------------------------ tidiness

    @Test
    @DisplayName("an old build is cleaned up rather than left to pile up")
    void tidiesUpOldBuilds() throws IOException {
        PackBuilder builder = new PackBuilder(work());
        Path first = zip("a.zip", Map.of("assets/a.txt", "one"));
        Path second = zip("b.zip", Map.of("assets/b.txt", "two"));

        builder.build(libraryOf(PackContribution.of("Claims", "icons", first),
                PackContribution.of("Records", "discs", second)), "Server pack").orElseThrow();

        Path changed = zip("c.zip", Map.of("assets/c.txt", "three"));
        PackBuild latest = builder.build(libraryOf(
                PackContribution.of("Claims", "icons", first),
                PackContribution.of("Records", "discs", changed)), "Server pack").orElseThrow();

        try (var stream = Files.list(work())) {
            assertThat(stream.toList())
                    .as("one zip per configuration change would fill a disk quietly")
                    .containsExactlyInAnyOrderElementsOf(
                            latest.parts().stream().map(PackPart::file).toList());
        }
    }
}
