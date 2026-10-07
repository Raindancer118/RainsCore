package de.raindancer.core.ui.chat;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The server's prefix design: read from {@code prefix.yml}, changed by {@code /prefix}, handed to
 * {@link Prefixes} so every plugin draws the change on its next line.
 */
public final class PrefixService {

    private final PrefixFile file;

    public PrefixService(PrefixFile file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Reads the file again and uses what it says. */
    public PrefixDesign reload() {
        PrefixDesign read = file.load();
        Prefixes.use(read);
        return read;
    }

    public PrefixDesign design() {
        return Prefixes.design();
    }

    /**
     * Applies {@code change} and saves it.
     *
     * @return whether it was saved. A file the owner broke by hand is not overwritten: the change still
     *         shows until the next reload, and the caller tells whoever made it why it will not last.
     */
    public synchronized boolean change(UnaryOperator<PrefixDesign> change) {
        PrefixDesign next = change.apply(Prefixes.design());
        Prefixes.use(next);
        return file.save(next);
    }

    public boolean isFileBroken() {
        return file.isBroken();
    }
}
