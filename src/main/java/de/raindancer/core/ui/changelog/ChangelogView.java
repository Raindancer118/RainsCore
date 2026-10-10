package de.raindancer.core.ui.changelog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Changelog entries as one chat message. */
public final class ChangelogView {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private ChangelogView() {
    }

    public static Component render(List<ChangelogEntry> entries, String heading, ZoneId zone) {
        var built = Component.text()
                .append(MINI.deserialize("<dark_gray><st>          </st> <gold><bold>"
                        + MINI.escapeTags(heading) + "</bold></gold> <dark_gray><st>          </st>"));
        for (ChangelogEntry entry : entries) {
            String when = entry.isPublished()
                    ? DAY.format(Instant.ofEpochMilli(entry.publishedAt()).atZone(zone))
                    : "draft " + entry.id();
            built.append(Component.newline())
                    .append(Component.text().color(NamedTextColor.YELLOW).append(MINI.deserialize(entry.title())))
                    .append(MINI.deserialize(" <dark_gray>· " + MINI.escapeTags(when)));
            for (String line : entry.lines()) {
                built.append(Component.newline())
                        .append(MINI.deserialize(" <dark_gray>•</dark_gray> "))
                        .append(Component.text().color(NamedTextColor.GRAY).append(MINI.deserialize(line)));
            }
        }
        built.append(Component.newline())
                .append(MINI.deserialize("<dark_gray>Read it again any time: <gray>/changelog"));
        return built.build();
    }
}
