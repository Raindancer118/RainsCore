package de.raindancer.core.pack;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The web server that hands the pack to a client.
 *
 * <p>Tested against a real socket rather than a mock, because everything that can go wrong here is
 * about the protocol and the filesystem — a wrong status code, a missing {@code Content-Length}, or
 * a path that escapes the folder. A mock would agree with whatever this class did.
 *
 * <p>The traversal tests are the ones with teeth. This is the only part of the library that opens a
 * port, and it serves files by name from a request; getting that wrong is how a resource pack server
 * becomes a way to read {@code server.properties}.
 */
@DisplayName("the pack server")
class PackServerTest {

    @TempDir
    Path directory;

    private PackServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private PackServer started() throws IOException {
        // Port 0: the operating system picks a free one, so the tests do not fight each other or
        // whatever else is on this machine.
        server = new PackServer(directory, "127.0.0.1", 0);
        server.start();
        return server;
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return send("GET", path);
    }

    private HttpResponse<byte[]> send(String method, String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + path))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    private void put(String name, String contents) throws IOException {
        Files.writeString(directory.resolve(name), contents);
    }

    // ------------------------------------------------------------------ serving

    @Test
    @DisplayName("a pack that is there is served")
    void servesAPack() throws Exception {
        put("pack-abc.zip", "pretend this is a zip");
        started();

        HttpResponse<byte[]> response = get(PackServer.PREFIX + "pack-abc.zip");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .isEqualTo("pretend this is a zip");
        assertThat(response.headers().firstValue("Content-Type")).contains("application/zip");
    }

    @Test
    @DisplayName("HEAD answers the size without the body")
    void answersHead() throws Exception {
        put("pack-abc.zip", "twenty-one characters");
        started();

        HttpResponse<byte[]> response = send("HEAD", PackServer.PREFIX + "pack-abc.zip");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Content-Length")).contains("21");
    }

    @Test
    @DisplayName("a pack that is not there is a plain 404")
    void missingIsNotFound() throws Exception {
        started();
        assertThat(get(PackServer.PREFIX + "nothing.zip").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("anything but GET and HEAD is refused")
    void refusesOtherMethods() throws Exception {
        put("pack-abc.zip", "x");
        started();
        assertThat(send("DELETE", PackServer.PREFIX + "pack-abc.zip").statusCode()).isEqualTo(405);
    }

    // ------------------------------------------------------------------ not serving anything else

    @Test
    @DisplayName("a name that climbs out of the folder is refused")
    void refusesTraversal() throws Exception {
        Files.writeString(directory.resolve("..").resolve("secret.txt").normalize(), "not yours");
        started();

        assertThat(get(PackServer.PREFIX + "..%2Fsecret.txt").statusCode())
                .as("serving files by name from a request is how a pack server becomes a way to "
                        + "read server.properties")
                .isEqualTo(404);
        assertThat(get(PackServer.PREFIX + "../secret.txt").statusCode()).isEqualTo(404);
        assertThat(get(PackServer.PREFIX + "sub/deeper.zip").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("there is no directory listing")
    void noListing() throws Exception {
        put("pack-abc.zip", "x");
        started();
        assertThat(get(PackServer.PREFIX).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ its own state

    @Test
    @DisplayName("it says where it is, so the URL is not written out twice")
    void namesItsOwnUrl() throws Exception {
        started();
        assertThat(server.urlFor("pack-abc.zip"))
                .isEqualTo("http://127.0.0.1:" + server.port() + PackServer.PREFIX + "pack-abc.zip");
    }

    @Test
    @DisplayName("a public address is used in the URL instead of what it binds to")
    void usesThePublicAddress() throws Exception {
        server = new PackServer(directory, "127.0.0.1", 0);
        server.publicAddress("http://play.example.com:8080");
        server.start();

        assertThat(server.urlFor("pack-abc.zip"))
                .as("binding to 0.0.0.0 or to localhost behind a proxy must not become the URL a "
                        + "client is told to download from")
                .isEqualTo("http://play.example.com:8080" + PackServer.PREFIX + "pack-abc.zip");
    }

    @Test
    @DisplayName("stopping it twice is not an error")
    void stopsIdempotently() throws Exception {
        started();
        assertThat(server.isRunning()).isTrue();
        server.stop();
        server.stop();
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    @DisplayName("a port that is taken fails with something a server owner can act on")
    void reportsATakenPort() throws Exception {
        started();
        PackServer second = new PackServer(directory, "127.0.0.1", server.port());
        try {
            assertThat(org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                    second::start))
                    .as("a silently dead pack server is indistinguishable from one nobody "
                            + "configured")
                    .isNotNull();
        } finally {
            second.stop();
        }
    }
}
