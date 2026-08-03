package de.raindancer.core.platform.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** SHA-1 helpers. The Minecraft protocol identifies a resource pack by its SHA-1. */
public final class Hashes {

    private Hashes() {
    }

    /** @return lowercase hex SHA-1 of {@code file} */
    public static String sha1(Path file) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * A stable pack UUID derived from what the pack actually is.
     *
     * <p>The client keys its downloaded packs by this id, so it has to stay the same for the
     * same content and change when the content changes — otherwise a client keeps serving an
     * outdated pack out of its cache. Deriving it from {@code id + sha1} gives exactly that.
     */
    public static UUID packId(String packId, String sha1) {
        return UUID.nameUUIDFromBytes((packId + "@" + sha1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("This JVM has no SHA-1 implementation.", e);
        }
    }
}
