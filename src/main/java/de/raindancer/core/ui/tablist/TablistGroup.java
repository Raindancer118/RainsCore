package de.raindancer.core.ui.tablist;

import java.util.List;

/**
 * The players in one world, under the heading that names it.
 *
 * @param label  what the heading says, e.g. "Nether"
 * @param symbol the character in front of it, so the list scans without being read
 */
public record TablistGroup(String world, String label, String symbol, List<TablistEntry> entries) {

    public TablistGroup {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }
}
