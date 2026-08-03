package de.raindancer.core.content.pack;

import java.nio.file.Path;

/**
 * One zip a client downloads.
 *
 * <p>There is more than one because the client stacks packs — it has since 1.20.3 — so the ordinary
 * case is every plugin's assets sent as its own pack, applied in order. Combining them into a single
 * zip is the other mode, and then there is exactly one of these.
 *
 * @param file  the zip, sitting in the folder that gets served
 * @param sha1  what it must hash to; this is what a client caches by
 * @param size  in bytes
 * @param label who it came from, for a log line or a menu
 */
public record PackPart(Path file, String sha1, long size, String label) {

    /** The name it is served under. */
    public String fileName() {
        return file.getFileName().toString();
    }
}
