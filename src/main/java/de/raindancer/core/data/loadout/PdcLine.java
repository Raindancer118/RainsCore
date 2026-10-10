package de.raindancer.core.data.loadout;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.stream.Collectors;

/** One persistent-data value as a line of text — {@code key type base64(text)} — and back. */
final class PdcLine {

    record Typed(String type, String text) {
    }

    record Entry(String key, Typed value) {
    }

    private PdcLine() {
    }

    static String write(String key, Typed value) {
        return key + " " + value.type() + " "
                + Base64.getEncoder().encodeToString(value.text().getBytes(StandardCharsets.UTF_8));
    }

    static Optional<Entry> read(String line) {
        // -1 keeps a trailing empty part: an empty text is written as nothing after the last space.
        String[] parts = line.split(" ", -1);
        if (parts.length != 3 || parts[0].isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Entry(parts[0], new Typed(parts[1],
                    new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8))));
        } catch (IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }

    static String join(int[] values) {
        return Arrays.stream(values).mapToObj(String::valueOf).collect(Collectors.joining(","));
    }

    static String join(long[] values) {
        return Arrays.stream(values).mapToObj(String::valueOf).collect(Collectors.joining(","));
    }

    static int[] ints(String text) {
        return text.isEmpty() ? new int[0] : Arrays.stream(text.split(",")).mapToInt(Integer::parseInt).toArray();
    }

    static long[] longs(String text) {
        return text.isEmpty() ? new long[0] : Arrays.stream(text.split(",")).mapToLong(Long::parseLong).toArray();
    }
}
