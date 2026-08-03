package de.raindancer.core.pack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * What a client is asked to download, and what went into it.
 *
 * <p>A list rather than a file, because the client stacks packs and the ordinary case is one pack
 * per contributing plugin. Combining them into a single zip gives a build with exactly one part.
 *
 * @param mode          how it was built
 * @param parts         the zips, in the order they are applied — last one wins a conflict
 * @param contributions how many plugins are in it
 * @param conflicts     files two plugins both wanted, where one lost; only combining can find these
 */
public record PackBuild(PackMode mode, List<PackPart> parts, int contributions,
                        List<String> conflicts) {

    public PackBuild {
        parts = parts == null ? List.of() : List.copyOf(parts);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    /**
     * What identifies this whole set.
     *
     * <p>Not any one part's hash: adding a second pack has to count as a change, or a player sent
     * the first one would never be offered the second. Derived from every part's hash in order, so
     * it changes when anything does and only then.
     */
    public String digest() {
        if (parts.size() == 1) {
            return parts.get(0).sha1();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            for (PackPart part : parts) {
                digest.update(part.sha1().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("This JVM has no SHA-1 implementation.", impossible);
        }
    }

    /** Everything a client would download, in bytes. */
    public long size() {
        return parts.stream().mapToLong(PackPart::size).sum();
    }

    public boolean isEmpty() {
        return parts.isEmpty();
    }

    /** Whether two plugins wanted the same file, so somebody's assets are not in there. */
    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }

    /** The size in a form a person reads, for a log line or a menu. */
    public String readableSize() {
        long size = size();
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return Math.round(size / 1024.0) + " KiB";
        }
        return String.format(Locale.ROOT, "%.1f MiB", size / (1024.0 * 1024.0));
    }
}
