package de.raindancer.core.content.pack;

/**
 * Whether the plugins' assets are sent as several packs or combined into one.
 *
 * <p>Worth having both, because the trade is real and depends on the server.
 */
public enum PackMode {

    /**
     * Each plugin's pack sent as its own, applied in order — what the client has supported since
     * 1.20.3.
     *
     * <p>The better default, and not only because it skips the merge. Nothing has to guess how two
     * plugins' files combine, so nothing can guess wrong; the client applies them the way it applies
     * anybody's stacked packs. And each pack is cached separately, so adding a plugin costs players
     * that plugin's download rather than the whole set again.
     *
     * <p>The cost is a download prompt per pack on clients that show one, and a limit on how many a
     * client will hold.
     */
    STACKED,

    /**
     * Everything merged into a single zip.
     *
     * <p>One download, one entry in the client's pack list. Worth it for a server sending many small
     * contributions, or one wanting language files and {@code sounds.json} genuinely merged key by
     * key rather than one pack's copy winning. The cost is that any change rebuilds the whole zip,
     * so every client downloads all of it again.
     */
    COMBINED
}
