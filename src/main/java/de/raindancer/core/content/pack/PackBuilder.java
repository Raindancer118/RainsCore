package de.raindancer.core.content.pack;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Hashes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Turns what the plugins offered into the one file a client downloads.
 *
 * <h2>The rule that matters most</h2>
 * The same contributions must always produce the same bytes. A client caches a resource pack by its
 * hash, so a build that varies — because a zip recorded the time it was written, or because two
 * plugins loaded in a different order — makes every player download the whole pack again on every
 * restart. That is not a crash and nothing logs it; it is a server that "feels slow to join", which
 * is why it can go unfixed for years. Hence fixed timestamps, sorted entries and an order that comes
 * from {@link PackLibrary} rather than from who asked first.
 *
 * <h2>One contribution is not merged</h2>
 * A single zip is served exactly as its plugin shipped it. Running it through the merger would
 * rewrite it into an identical-but-not-byte-identical file, changing its hash for no reason — the
 * very thing the paragraph above is about.
 *
 * <h2>Failure</h2>
 * A build that cannot be done answers empty and says why in {@link #problems()}. It never throws and
 * never half-writes: the previous build stays in place and stays servable, because a server whose
 * pack fails to rebuild should keep serving the pack it already had rather than none at all.
 */
public final class PackBuilder {

    private static final LogChannel log = Log.of("pack");

    /** 2001-09-09, the same fixed stamp the merger uses, so folders and merges agree. */
    private static final long FIXED_TIME = 1_000_000_000_000L;

    /** What this writes is named so it can recognise — and clean up — its own output. */
    private static final String BUILT_PREFIX = "pack-";

    private final Path workFolder;
    private final PackMerger merger = new PackMerger();
    private final List<String> problems = new ArrayList<>();
    private volatile PackMode mode = PackMode.STACKED;

    /** @param workFolder where built zips are kept; owned by this class, cleaned up by it */
    public PackBuilder(Path workFolder) {
        this.workFolder = workFolder;
    }

    /**
     * Whether the contributions are sent as several packs or merged into one.
     *
     * <p>Stacked unless told otherwise — see {@link PackMode}.
     */
    public void mode(PackMode mode) {
        this.mode = mode == null ? PackMode.STACKED : mode;
    }

    public PackMode mode() {
        return mode;
    }

    /** What went wrong with the last build, in the words the log used. */
    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    /**
     * Builds the pack.
     *
     * <p>Blocking, and does real work on real files — call it off the main thread.
     *
     * @param description what the client shows in its pack list
     * @return the build, or empty when there is nothing to build or it could not be done
     */
    public Optional<PackBuild> build(PackLibrary library, String description) {
        synchronized (this) {
            problems.clear();
        }
        if (library == null || library.isEmpty()) {
            return Optional.empty();
        }

        List<PackContribution> contributions = library.all();
        List<Path> sources;
        try {
            sources = prepare(contributions);
        } catch (IOException failure) {
            return refuse("could not read a contributed pack: " + failure.getMessage());
        }

        // One contribution is one pack either way: there is nothing to stack and nothing to merge.
        if (sources.size() == 1 || mode == PackMode.STACKED) {
            return stacked(sources, contributions);
        }
        return merged(sources, description, contributions.size());
    }

    // ---------------------------------------------------------------------------- the two paths

    /**
     * Every contribution as its own pack, byte-for-byte as its plugin shipped it.
     *
     * <p>Copied rather than merged, so the bytes and therefore the hashes are exactly the plugins'
     * own — which is what lets a client keep the ones it already has when a new plugin is added.
     *
     * <p>Copied rather than referenced in place, which is what this did first and what a live server
     * caught within a minute. The web server serves one folder; a build pointing at a file inside
     * some other plugin's data folder is a build every client gets a 404 for. It was invisible in
     * the unit tests because they asserted the path was untouched — which was asserting the bug.
     */
    private Optional<PackBuild> stacked(List<Path> sources, List<PackContribution> contributions) {
        try {
            Files.createDirectories(workFolder);
            List<PackPart> parts = new ArrayList<>(sources.size());
            List<Path> keep = new ArrayList<>(sources.size());
            for (int at = 0; at < sources.size(); at++) {
                Path source = sources.get(at);
                String wrong = whyItIsNotAPack(source);
                if (wrong != null) {
                    // Dropped rather than failing the build: one plugin shipping something that is
                    // not a resource pack must not cost everybody else theirs. Combining catches
                    // this for free by having to read the zip; stacking never opens it, so it has
                    // to look — otherwise the first anyone knows is a failed download on every
                    // client at once.
                    note(contributions.get(at).id() + " is not a usable resource pack (" + wrong
                            + "); it is left out");
                    continue;
                }
                String sha1 = Hashes.sha1(source);
                Path named = workFolder.resolve(BUILT_PREFIX + sha1.substring(0, 12) + ".zip");
                if (!named.equals(source)) {
                    // Copied beside it and moved into place, rather than copied straight in. The
                    // folder this writes to is the one the web server serves, so a copy that failed
                    // halfway would leave a truncated zip being handed to every client — and it
                    // would have replaced the good one of the same name on the way.
                    Path partial = named.resolveSibling(named.getFileName() + ".writing");
                    try {
                        Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
                        Files.move(partial, named, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(partial);
                    }
                }
                keep.add(named);
                parts.add(new PackPart(named, sha1, Files.size(named), contributions.get(at).id()));
            }
            tidy(keep);
            if (parts.isEmpty()) {
                return refuse("none of the contributed packs could be used");
            }
            return Optional.of(new PackBuild(PackMode.STACKED, parts, parts.size(), List.of()));
        } catch (IOException failure) {
            return refuse("could not read a contributed pack: " + failure.getMessage());
        }
    }

    /** Several, combined. */
    private Optional<PackBuild> merged(List<Path> sources, String description, int contributions) {
        try {
            Files.createDirectories(workFolder);
            PackMerger.Result result = merger.merge(sources, workFolder,
                    description == null || description.isBlank() ? "Server pack" : description);

            // The merger names its own output; this renames it to what this class cleans up, so a
            // rebuild does not leave the previous pack behind for ever.
            Path named = workFolder.resolve(BUILT_PREFIX + result.sha1().substring(0, 12) + ".zip");
            if (!named.equals(result.file())) {
                Files.move(result.file(), named, StandardCopyOption.REPLACE_EXISTING);
            }
            tidy(List.of(named));

            if (!result.conflicts().isEmpty()) {
                // Loud on purpose: somebody's textures are not in the pack, and the plugin that lost
                // has no way of finding that out for itself.
                log.warn("{} file(s) were wanted by more than one plugin; the later one won: {}",
                        result.conflicts().size(), String.join(", ", result.conflicts()));
            }
            PackPart one = new PackPart(named, result.sha1(), Files.size(named), "combined");
            return Optional.of(new PackBuild(PackMode.COMBINED, List.of(one), contributions,
                    result.conflicts()));
        } catch (PackMerger.MergeException | IOException failure) {
            return refuse(failure.getMessage());
        }
    }

    // ---------------------------------------------------------------------------- helpers

    /**
     * Every contribution as a zip, in the order they are applied.
     *
     * <p>A plugin may contribute a folder — which is what working on assets actually looks like,
     * rather than rezipping by hand after every change — so those are zipped here.
     */
    private List<Path> prepare(List<PackContribution> contributions) throws IOException {
        List<Path> sources = new ArrayList<>(contributions.size());
        for (PackContribution contribution : contributions) {
            Path source = contribution.source();
            sources.add(Files.isDirectory(source) ? zipped(contribution) : source);
        }
        return sources;
    }

    /**
     * A folder, as a zip.
     *
     * <p>Sorted and with a fixed timestamp, so the same folder always produces the same bytes — see
     * the note on the class. Without that, contributing a folder rather than a zip would quietly
     * cost every player a redownload per restart.
     */
    private Path zipped(PackContribution contribution) throws IOException {
        Files.createDirectories(workFolder);
        Path folder = contribution.source();
        Path target = workFolder.resolve("from-" + contribution.id().replace(':', '-')
                .replaceAll("[^a-z0-9-]", "-") + ".zip");

        List<Path> files;
        try (var walk = Files.walk(folder)) {
            files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> folder.relativize(path).toString()))
                    .toList();
        }
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Path file : files) {
                ZipEntry entry = new ZipEntry(folder.relativize(file).toString().replace('\\', '/'));
                entry.setTime(FIXED_TIME);
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        } catch (IOException failure) {
            // Half a zip left in the folder is half a zip that is still there tomorrow, and one
            // unreadable asset repeated over a week fills a disk quietly.
            Files.deleteIfExists(target);
            throw failure;
        }
        return target;
    }

    /**
     * Removes everything this class built except what is now in use.
     *
     * <p>A zip per configuration change is how a plugin folder quietly grows to gigabytes. Only
     * files this class named are touched — a pack somebody dropped in by hand is not ours to delete.
     *
     * @param keep every part of the build now in use; stacked mode has several
     */
    private void tidy(List<Path> keep) {
        try (var stream = Files.list(workFolder)) {
            for (Path old : stream.toList()) {
                String name = old.getFileName().toString();
                boolean ours = name.startsWith(BUILT_PREFIX) || name.startsWith("from-")
                        || name.startsWith("combined");
                if (ours && !keep.contains(old)) {
                    Files.deleteIfExists(old);
                }
            }
        } catch (IOException failure) {
            // Untidy, not broken. The pack that was just built is still there and still servable.
            log.warn("Could not tidy up {} ({})", workFolder, failure.getMessage());
        }
    }

    /**
     * Why a file is not a resource pack, or null when it is one.
     *
     * <p>Only the two things that make a client reject it outright: it has to be a zip, and it has
     * to have a {@code pack.mcmeta}. Anything beyond that is the client's business and not worth
     * guessing at here.
     */
    private static String whyItIsNotAPack(Path file) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
            return zip.getEntry("pack.mcmeta") == null ? "it has no pack.mcmeta" : null;
        } catch (IOException | RuntimeException notAZip) {
            return "it is not a zip";
        }
    }

    private void note(String problem) {
        synchronized (this) {
            problems.add(problem);
        }
        log.warn(problem);
    }

    private Optional<PackBuild> refuse(String problem) {
        synchronized (this) {
            problems.add(problem);
        }
        log.error("The resource pack could not be built: {}", problem);
        return Optional.empty();
    }
}
