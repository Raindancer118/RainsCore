package de.raindancer.core.ui.changelog;

import java.util.List;

/**
 * One update as players are told about it.
 *
 * @param title       MiniMessage
 * @param lines       MiniMessage, one point each
 * @param publishedAt when it went out; 0 while it is a draft
 */
public record ChangelogEntry(String id, String title, List<String> lines, long publishedAt) {

    public ChangelogEntry {
        lines = List.copyOf(lines);
    }

    public boolean isPublished() {
        return publishedAt > 0;
    }
}
