package de.raindancer.core.pack;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything the plugins have offered to put on a player's screen, in the order it is applied.
 *
 * <h2>Why this is arbitration and not a list</h2>
 * A player has one resource pack. Two plugins each sending their own means the second one wins and
 * the first one's textures are simply gone — the failure nobody can debug, because nothing went
 * wrong anywhere that anybody logs. So the plugins offer, this decides, and a conflict is something
 * a server owner can read rather than a mystery.
 *
 * <h2>Why the order is fixed rather than "whoever asked first"</h2>
 * Two reasons, and both bite. The merger resolves a duplicate file in favour of the pack applied
 * last, so load order deciding priority would mean a texture changing hands because a plugin was
 * renamed. And the combined zip is only worth caching if it is reproducible: if the same set of
 * contributions can produce two different zips, every client redownloads the pack on every restart.
 * Priority first, then id — never insertion.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. Plugins offer during their own {@code onEnable}, which on Paper is not a
 * single thread's business any more.
 */
public final class PackLibrary {

    private static final LogChannel log = Log.of("pack");

    /**
     * Applied last wins, and a tie goes to whichever id sorts first — so it is a property of what
     * was offered rather than of when.
     */
    private static final Comparator<PackContribution> ORDER =
            Comparator.<PackContribution>comparingInt(PackContribution::priority)
                    .thenComparing(PackContribution::id);

    private final Map<String, PackContribution> offered = new ConcurrentHashMap<>();
    private final List<String> problems = new ArrayList<>();

    /**
     * Takes a plugin's assets, replacing anything it offered under the same name.
     *
     * @return whether it was taken; false means the source is not there and the reason is in
     *         {@link #problems()}
     */
    public boolean offer(PackContribution contribution) {
        if (contribution == null) {
            return false;
        }
        // Checked now rather than at build time. A plugin that ships a pack it forgot to include
        // should hear about it while somebody is looking at the startup log, not twenty minutes
        // later when a player is waiting on a download that cannot be built.
        if (!Files.exists(contribution.source())) {
            note(contribution.id() + " offers " + contribution.source()
                    + ", which is not there; it is ignored");
            return false;
        }
        PackContribution previous = offered.put(contribution.id(), contribution);
        if (previous != null && !previous.source().equals(contribution.source())) {
            log.info("{} replaced its own contribution ({} → {})", contribution.id(),
                    previous.source().getFileName(), contribution.source().getFileName());
        }
        return true;
    }

    /** Takes one back. Answers whether there was one. */
    public boolean withdraw(String id) {
        return id != null && offered.remove(id.toLowerCase(Locale.ROOT)) != null;
    }

    /**
     * Takes back everything one plugin offered — for a plugin being disabled.
     *
     * <p>Without this, a plugin that is removed leaves its textures in the pack for ever, and the
     * server owner's only clue is a zip they cannot account for.
     *
     * @return how many were taken back
     */
    public int withdrawAllFrom(String owner) {
        if (owner == null) {
            return 0;
        }
        String wanted = owner.toLowerCase(Locale.ROOT);
        List<String> theirs = offered.values().stream()
                .filter(contribution -> contribution.owner().toLowerCase(Locale.ROOT).equals(wanted))
                .map(PackContribution::id)
                .toList();
        theirs.forEach(offered::remove);
        return theirs.size();
    }

    /** One, by {@link PackContribution#id()}. */
    public Optional<PackContribution> byId(String id) {
        return id == null ? Optional.empty()
                : Optional.ofNullable(offered.get(id.toLowerCase(Locale.ROOT)));
    }

    /** Everything, in the order it is applied — first offered to the client, last wins. */
    public List<PackContribution> all() {
        return offered.values().stream().sorted(ORDER).toList();
    }

    /** Just the files, in the same order, which is what the merger wants. */
    public List<Path> sources() {
        return all().stream().map(PackContribution::source).toList();
    }

    /** Whether there is anything to build at all. */
    public boolean isEmpty() {
        return offered.isEmpty();
    }

    public int size() {
        return offered.size();
    }

    /** What was refused and why, in the words the log used. */
    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    /** Forgets the refusals — for a rebuild, so old complaints are not reported twice. */
    public synchronized void forgetProblems() {
        problems.clear();
    }

    private void note(String problem) {
        synchronized (this) {
            problems.add(problem);
        }
        log.warn(problem);
    }
}
