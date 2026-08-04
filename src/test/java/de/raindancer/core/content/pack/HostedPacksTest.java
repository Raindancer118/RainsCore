package de.raindancer.core.content.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A pack that is already hosted somewhere, rather than built here.
 *
 * <h2>Why this exists</h2>
 * Everything else in this package builds a pack out of plugins' own assets and serves it. A server
 * also wants the other thing: one whole pack, made by hand, hosted somewhere, applied to everybody —
 * a texture pack the server is themed around.
 *
 * <p>Before this, a plugin wanting that had exactly one route: call {@code setResourcePack} itself.
 * Which works, once. The moment anything contributes assets to Core's pack the two fight over the
 * player's <em>single</em> pack slot, whoever sends last wins, and the loser's is silently gone —
 * the collision this whole package exists to arbitrate, reintroduced by the one plugin that went
 * round it.
 *
 * <h2>Why a hosted pack goes on first</h2>
 * The client applies packs in order and the last one wins a conflict. A server's own texture pack is
 * the <em>base</em>; the plugins' assets — a custom item's model, a custom sound — are the specific
 * things layered on top and must not be overwritten by it. So hosted first, built after.
 */
@DisplayName("hosted resource packs")
class HostedPacksTest {

    @TempDir
    Path directory;

    private final List<List<PackOffer>> sent = new ArrayList<>();

    private static final UUID SOMEBODY = UUID.randomUUID();

    /** A real-looking sha1, because a pack's hash is what a client caches by. */
    private static final String A_HASH = "9da2d07b71bf028fd9da9e9260facf2e52916b63";
    private static final String ANOTHER_HASH = "1111111111111111111111111111111111111111";

    private ResourcePacks packs() {
        ResourcePacks packs = new ResourcePacks(directory.resolve("work"), new PackSink() {
            @Override
            public void send(UUID player, List<PackOffer> offers) {
                sent.add(offers);
            }

            @Override
            public void clear(UUID player) {
            }
        });
        packs.urls(name -> "http://localhost:8080/packs/" + name);
        return packs;
    }

    private Path zip(String name) throws IOException {
        Path file = directory.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write("{\"pack\":{\"description\":\"x\",\"pack_format\":46}}"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/minecraft/x.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    // ------------------------------------------------------------------ taking one

    @Nested
    @DisplayName("registering one")
    class Registering {

        @Test
        @DisplayName("a hosted pack is taken and listed")
        void itIsTaken() {
            ResourcePacks packs = packs();

            assertThat(packs.host(HostedPack.at("yeukpack", "https://example.test/yeukpack.zip",
                    A_HASH))).isTrue();
            assertThat(packs.hosted()).extracting(HostedPack::id).containsExactly("yeukpack");
        }

        @Test
        @DisplayName("one with no url or no hash is refused, and says so")
        void nonsenseIsRefused() {
            // A pack offered with no hash is one the client re-downloads on every join, because the
            // hash is what it caches by. Refused rather than sent, and the refusal is readable.
            ResourcePacks packs = packs();

            assertThat(packs.host(HostedPack.at("a", "", A_HASH))).isFalse();
            assertThat(packs.host(HostedPack.at("b", "https://example.test/p.zip", ""))).isFalse();
            assertThat(packs.host(null)).isFalse();
            assertThat(packs.problems()).isNotEmpty();
        }

        @Test
        @DisplayName("a hash that is not a sha1 is refused")
        void aWrongShapedHashIsRefused() {
            // Not pedantry: a client given a malformed hash rejects the pack, and the server sees a
            // download failure with nothing saying the hash was the problem.
            assertThat(packs().host(HostedPack.at("a", "https://example.test/p.zip", "nope")))
                    .isFalse();
        }

        @Test
        @DisplayName("registering the same id again replaces it rather than sending two")
        void theSameIdIsOnePack() {
            ResourcePacks packs = packs();
            packs.host(HostedPack.at("yeukpack", "https://example.test/one.zip", A_HASH));
            packs.host(HostedPack.at("yeukpack", "https://example.test/two.zip", ANOTHER_HASH));

            assertThat(packs.hosted()).hasSize(1);
            assertThat(packs.hosted().getFirst().url()).isEqualTo("https://example.test/two.zip");
        }

        @Test
        @DisplayName("it can be taken back, for a module being disabled")
        void itCanBeWithdrawn() {
            ResourcePacks packs = packs();
            packs.host(HostedPack.at("yeukpack", "https://example.test/p.zip", A_HASH));

            assertThat(packs.unhost("yeukpack")).isTrue();
            assertThat(packs.hosted()).isEmpty();
            assertThat(packs.unhost("yeukpack")).as("twice is not an error").isFalse();
        }
    }

    // ------------------------------------------------------------------ sending it

    @Nested
    @DisplayName("sending it to somebody")
    class Sending {

        @Test
        @DisplayName("a hosted pack alone is sent, with no local pack at all")
        void hostedAloneIsEnough() {
            // The whole point for a server that builds nothing of its own. Before this, sendTo
            // returned early on an empty build and a hosted pack could never be sent.
            ResourcePacks packs = packs();
            packs.host(HostedPack.at("yeukpack", "https://example.test/yeukpack.zip", A_HASH));

            packs.sendTo(SOMEBODY);

            assertThat(sent).hasSize(1);
            assertThat(sent.getFirst()).hasSize(1);
            assertThat(sent.getFirst().getFirst().url())
                    .isEqualTo("https://example.test/yeukpack.zip");
            assertThat(sent.getFirst().getFirst().sha1()).isEqualTo(A_HASH);
        }

        @Test
        @DisplayName("its own url is used exactly, never the local pack server's")
        void itKeepsItsOwnUrl() {
            // urls() rewrites a *built* part's file name into a download link. A hosted pack already
            // has an absolute one, and putting it through that would point the client at this
            // server for a file that is not here.
            ResourcePacks packs = packs();
            packs.host(HostedPack.at("yeukpack", "https://mc-packs.example/yeukpack/yeukpack.zip",
                    A_HASH));

            packs.sendTo(SOMEBODY);

            assertThat(sent.getFirst().getFirst().url())
                    .isEqualTo("https://mc-packs.example/yeukpack/yeukpack.zip");
        }

        @Test
        @DisplayName("hosted packs come before the built one, so plugin assets win a conflict")
        void hostedFirstThenBuilt() throws IOException {
            ResourcePacks packs = packs();
            packs.host(HostedPack.at("yeukpack", "https://example.test/yeukpack.zip", A_HASH));
            packs.contribute(PackContribution.of("SomePlugin", "items", zip("items.zip")));
            packs.rebuild();

            packs.sendTo(SOMEBODY);

            assertThat(sent).hasSize(1);
            List<PackOffer> offers = sent.getFirst();
            assertThat(offers).hasSize(2);
            assertThat(offers.getFirst().url())
                    .as("the server's own pack is the base; a plugin's model must not be painted "
                            + "over by it")
                    .isEqualTo("https://example.test/yeukpack.zip");
            assertThat(offers.get(1).url()).contains("localhost");
        }

        @Test
        @DisplayName("everything goes in one request, as one prompt")
        void oneRequest() throws IOException {
            ResourcePacks packs = packs();
            packs.host(HostedPack.at("a", "https://example.test/a.zip", A_HASH));
            packs.host(HostedPack.at("b", "https://example.test/b.zip", ANOTHER_HASH));
            packs.contribute(PackContribution.of("SomePlugin", "items", zip("items.zip")));
            packs.rebuild();

            packs.sendTo(SOMEBODY);

            assertThat(sent).as("one at a time is one prompt each and one chance each to be "
                    + "misordered").hasSize(1);
            assertThat(sent.getFirst()).hasSize(3);
        }

        @Test
        @DisplayName("whether it is required follows the same setting as everything else")
        void requiredFollowsTheSetting() {
            ResourcePacks packs = packs();
            packs.required(true);
            packs.host(HostedPack.at("yeukpack", "https://example.test/p.zip", A_HASH));

            packs.sendTo(SOMEBODY);

            assertThat(sent.getFirst().getFirst().required())
                    .as("a server that forces its pack forces all of it — one required and one not "
                            + "is a rule nobody could describe")
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------ not sending it twice

    @Nested
    @DisplayName("not sending it over and over")
    class SendingOnce {

        @Test
        @DisplayName("the same player is not sent the same set twice")
        void onceIsEnough() {
            ResourcePacks packs = packs();
            packs.host(HostedPack.at("yeukpack", "https://example.test/p.zip", A_HASH));

            packs.sendTo(SOMEBODY);
            packs.sendTo(SOMEBODY);

            assertThat(sent).hasSize(1);
        }

        @Test
        @DisplayName("changing the hosted pack does send again")
        void aChangeIsSentAgain() {
            // The bug this closes by construction: with the digest taken from the built parts alone,
            // a player already wearing the built pack would never be offered a hosted one added
            // afterwards — and on a server that builds nothing, never offered anything at all.
            ResourcePacks packs = packs();
            packs.host(HostedPack.at("yeukpack", "https://example.test/one.zip", A_HASH));
            packs.sendTo(SOMEBODY);

            packs.host(HostedPack.at("yeukpack", "https://example.test/two.zip", ANOTHER_HASH));
            packs.sendTo(SOMEBODY);

            assertThat(sent).hasSize(2);
        }

        @Test
        @DisplayName("withdrawing it sends again, so nobody is left wearing a pack that is gone")
        void withdrawingIsAChangeToo() {
            ResourcePacks packs = packs();
            packs.host(HostedPack.at("yeukpack", "https://example.test/p.zip", A_HASH));
            packs.sendTo(SOMEBODY);

            packs.unhost("yeukpack");
            packs.sendTo(SOMEBODY);

            assertThat(sent).as("nothing left to send, so nothing is sent — and the next real pack "
                    + "will not be mistaken for the one they already have").hasSize(1);
        }

        @Test
        @DisplayName("nothing at all is sent when there is nothing to send")
        void nothingMeansNothing() {
            packs().sendTo(SOMEBODY);

            assertThat(sent)
                    .as("sending a pack that does not exist is a download error for no reason")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the value itself")
    class TheValue {

        @Test
        @DisplayName("it needs an id, a url and a hash")
        void itIsCheckedOnTheWayIn() {
            assertThat(HostedPack.at("a", "https://example.test/p.zip", A_HASH).isUsable()).isTrue();
            assertThat(HostedPack.at("", "https://example.test/p.zip", A_HASH).isUsable()).isFalse();
            assertThat(HostedPack.at("a", null, A_HASH).isUsable()).isFalse();
            assertThat(HostedPack.at("a", "https://example.test/p.zip", null).isUsable()).isFalse();
        }

        @Test
        @DisplayName("the hash is read case-insensitively, because people paste it either way")
        void theHashIsNormalised() {
            assertThat(HostedPack.at("a", "https://example.test/p.zip", A_HASH.toUpperCase())
                    .sha1()).isEqualTo(A_HASH);
        }

        @Test
        @DisplayName("its id is stable, so the client caches it rather than re-downloading")
        void theIdIsStable() {
            // The offer's UUID is what a client uses to recognise a pack it already has. Derived
            // from the id and the hash, so the same pack is the same pack across restarts and a
            // changed pack is a new one.
            HostedPack pack = HostedPack.at("yeukpack", "https://example.test/p.zip", A_HASH);

            assertThat(pack.offerId()).isEqualTo(
                    HostedPack.at("yeukpack", "https://example.test/p.zip", A_HASH).offerId());
            assertThat(pack.offerId()).isNotEqualTo(
                    HostedPack.at("yeukpack", "https://example.test/p.zip", ANOTHER_HASH).offerId());
        }

        @Test
        @DisplayName("nothing here throws on rubbish")
        void itNeverThrows() {
            assertThatCode(() -> HostedPack.at(null, null, null)).doesNotThrowAnyException();
        }
    }
}
