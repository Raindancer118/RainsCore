package de.raindancer.core.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.core.util.Hashes;
import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;

/**
 * Combines several resource packs into one zip.
 *
 * <p>The client has been able to apply several packs at once since 1.20.3, so combining is a
 * convenience, not a necessity — it turns three download prompts into one. The merge rules mirror
 * the ones the pack repository itself uses when it stacks modules into a pack:
 *
 * <ul>
 *   <li>{@code assets/*&#47;lang/*.json} and {@code assets/*&#47;sounds.json} are merged key by key,</li>
 *   <li>{@code pack.mcmeta} is rebuilt with the intersection of the packs' format ranges,</li>
 *   <li>{@code pack.png} comes from the first pack that has one,</li>
 *   <li>every other duplicate path is won by the pack that is applied last — same as what the
 *       client would do when applying the packs stacked — and is reported as a conflict.</li>
 * </ul>
 *
 * <p>The output is byte-for-byte reproducible: entries are written sorted with a fixed timestamp,
 * so an unchanged set of packs keeps its sha1 and clients do not redownload it.
 *
 * <h2>Where this came from</h2>
 * Adapted from {@code RainsResourcepackManager}, which had it first and had it right. It is here
 * because merging is not a pack manager's private business: a player can have exactly one resource
 * pack applied, so the moment two plugins each want to contribute assets — a claims module with
 * menu icons, a record seller with custom discs — somebody has to combine them or the last one to
 * send wins and the rest silently vanish. That is a job for the foundation, and this is the piece
 * of it that was already solved. Only the logger and the package changed.
 */
public final class PackMerger {

    /** 2001-09-09, the same fixed stamp the pack repository uses, for reproducible zips. */
    private static final long FIXED_TIME = 1_000_000_000_000L;

    /** What came out of a merge. */
    public record Result(Path file, String sha1, long size, List<String> conflicts,
                         int minFormat, int maxFormat) {
    }

    /** A pack that cannot be merged, with the reason a human needs. */
    public static class MergeException extends Exception {
        public MergeException(String message) {
            super(message);
        }

        public MergeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final LogChannel log = Log.of("pack");

    public PackMerger() {
    }

    /**
     * Merges {@code sources} (in application order) into {@code targetFolder}.
     *
     * <p>Blocking and CPU/IO bound; call from the worker thread.
     */
    public Result merge(List<Path> sources, Path targetFolder, String description)
            throws MergeException {
        if (sources.size() < 2) {
            throw new MergeException("Combining needs at least two packs with a local copy.");
        }

        Map<String, byte[]> out = new LinkedHashMap<>();
        Map<String, String> owner = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        int minFormat = Integer.MIN_VALUE;
        int maxFormat = Integer.MAX_VALUE;

        for (Path source : sources) {
            String label = source.getFileName().toString();
            try (ZipFile zip = new ZipFile(source.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = normalise(entry.getName());
                    if (name == null) {
                        log.warn("Ignoring suspicious entry '{}' in {}", entry.getName(), label);
                        continue;
                    }
                    byte[] data = read(zip, entry);

                    if (name.equals("pack.mcmeta")) {
                        int[] range = formats(data);
                        minFormat = Math.max(minFormat, range[0]);
                        maxFormat = Math.min(maxFormat, range[1]);
                        continue;
                    }
                    if (name.equals("pack.png")) {
                        out.putIfAbsent(name, data);
                        owner.putIfAbsent(name, label);
                        continue;
                    }
                    if (out.containsKey(name)) {
                        if (isJsonMergeable(name)) {
                            out.put(name, mergeJson(out.get(name), data, name));
                            continue;
                        }
                        if (java.util.Arrays.equals(out.get(name), data)) {
                            continue;
                        }
                        conflicts.add(name + " (" + owner.get(name) + " → " + label + ")");
                    }
                    out.put(name, data);
                    owner.put(name, label);
                }
            } catch (IOException e) {
                throw new MergeException("Could not read " + label + ": " + e.getMessage(), e);
            }
        }

        if (minFormat == Integer.MIN_VALUE) {
            throw new MergeException("None of the packs has a readable pack.mcmeta.");
        }
        if (minFormat > maxFormat) {
            throw new MergeException("These packs do not share a single pack format "
                    + "(needed " + minFormat + " … " + maxFormat + "). "
                    + "Send them stacked instead: /rrp mode stacked");
        }
        out.put("pack.mcmeta", mcmeta(description, minFormat, maxFormat));

        try {
            Files.createDirectories(targetFolder);
            Path temp = targetFolder.resolve("combined.zip.tmp");
            write(out, temp);
            String sha1 = Hashes.sha1(temp);
            Path target = targetFolder.resolve("combined-" + sha1.substring(0, 12) + ".zip");
            // Old combinations are RRP's own build output — cleaning them up keeps the folder
            // from growing one zip per configuration change.
            try (var stream = Files.list(targetFolder)) {
                for (Path old : stream.toList()) {
                    String fileName = old.getFileName().toString();
                    if (fileName.startsWith("combined-") && !old.equals(target)) {
                        Files.deleteIfExists(old);
                    }
                }
            }
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new Result(target, sha1, Files.size(target), List.copyOf(conflicts),
                    minFormat, maxFormat);
        } catch (IOException e) {
            throw new MergeException("Could not write the combined pack: " + e.getMessage(), e);
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Rejects absolute paths and {@code ..} segments — a zip must not write outside itself. */
    private static String normalise(String name) {
        String clean = name.replace('\\', '/');
        if (clean.startsWith("/") || clean.contains("../") || clean.equals("..")) {
            return null;
        }
        return clean;
    }

    private static boolean isJsonMergeable(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".json") || !lower.startsWith("assets/")) {
            return false;
        }
        return lower.contains("/lang/") || lower.endsWith("/sounds.json");
    }

    private byte[] mergeJson(byte[] first, byte[] second, String name) {
        try {
            JsonObject a = JsonParser.parseString(new String(first, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject b = JsonParser.parseString(new String(second, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : b.entrySet()) {
                a.add(entry.getKey(), entry.getValue());
            }
            return a.toString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            log.warn("Could not merge {} as JSON ({}), keeping the later pack's version.",
                    name, e.getMessage());
            return second;
        }
    }

    /** @return {@code [min, max]} pack format range declared by a pack.mcmeta */
    private static int[] formats(byte[] data) {
        try {
            JsonObject pack = JsonParser.parseString(new String(data, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("pack");
            int format = pack.has("pack_format") ? pack.get("pack_format").getAsInt() : -1;
            int min = pack.has("min_format") ? pack.get("min_format").getAsInt() : format;
            int max = pack.has("max_format") ? pack.get("max_format").getAsInt() : format;
            if (min < 0 && max < 0) {
                return new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE};
            }
            return new int[]{min < 0 ? max : min, max < 0 ? min : max};
        } catch (RuntimeException e) {
            return new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE};
        }
    }

    private static byte[] mcmeta(String description, int min, int max) {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", description);
        // min_format/max_format only: "supported_formats" is rejected from format 82 on, and the
        // two-key form is understood by 1.21.x as well.
        pack.addProperty("min_format", min);
        pack.addProperty("max_format", max);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return (root.toString() + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static void write(Map<String, byte[]> entries, Path target) throws IOException {
        List<String> names = new ArrayList<>(entries.keySet());
        names.sort(String::compareTo);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            for (String name : names) {
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(FIXED_TIME);
                zip.putNextEntry(entry);
                zip.write(entries.get(name));
                zip.closeEntry();
            }
        }
    }
}
