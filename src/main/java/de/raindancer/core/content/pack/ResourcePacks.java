package de.raindancer.core.content.pack;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Hashes;
import net.kyori.adventure.text.Component;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * The one owner of what is on a player's screen.
 *
 * <h2>Why this is Core's and not a pack manager's</h2>
 * Because a player has one resource pack. The moment two plugins each have assets — a claims module
 * with menu icons, a record seller with custom discs — whichever sends last wins and the other one's
 * textures are simply not there. Nothing errors, nothing logs, and the plugin that lost has no way
 * of finding out. That is the same collision as the action bar and the sidebar, and it gets the same
 * answer: plugins offer, this decides, and a conflict is something a server owner can read.
 *
 * <p>It is deliberately not a pack <em>manager</em>. It does not browse catalogues, install packs
 * for players, or have opinions about where assets come from — a plugin doing that can still do it,
 * and contribute the result here.
 *
 * <h2>How a plugin uses it</h2>
 * <pre>{@code
 * core.resourcePacks().contribute(
 *         PackContribution.of("Claims", "icons", getDataFolder().toPath().resolve("icons.zip"))
 *                 .describedAs("The icons the claim menu uses"));
 * }</pre>
 * Contributing is all a plugin does. Building, serving and sending are this class's business, and
 * when to send is not a decision a plugin should be making per player.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. {@link #rebuild()} does real work on real files and blocks — call it off the
 * main thread; everything else is bookkeeping.
 */
public final class ResourcePacks {

    private static final LogChannel log = Log.of("pack");

    private final PackLibrary library = new PackLibrary();
    private final PackBuilder builder;
    private final PackSink sink;

    /** What each player was last sent, by the build's digest — so the same set is not sent twice. */
    private final Map<UUID, String> sentDigest = new ConcurrentHashMap<>();
    private final Map<UUID, PackStatus> status = new ConcurrentHashMap<>();

    private volatile PackBuild current;
    private volatile boolean required;
    private volatile String description = "Server pack";
    private volatile Component prompt;
    /** How a built file's name becomes a URL. Set by whoever is serving it. */
    private volatile UnaryOperator<String> urls = name -> "";

    public ResourcePacks(Path workFolder, PackSink sink) {
        this.builder = new PackBuilder(workFolder);
        this.sink = sink;
    }

    // ---------------------------------------------------------------------------- settings

    /**
     * Whether refusing the pack means being disconnected.
     *
     * <p>Off by default, and worth leaving off unless the server genuinely does not work without it.
     * A required pack turns every download failure — a proxy, a slow connection, a client bug — into
     * a player who cannot join and does not know why.
     */
    public void required(boolean required) {
        this.required = required;
    }

    public boolean isRequired() {
        return required;
    }

    /** What the client shows in its list of packs. */
    public void description(String description) {
        this.description = description == null || description.isBlank()
                ? "Server pack" : description.trim();
    }

    /**
     * Whether the contributions go out as several packs or merged into one.
     *
     * <p>Stacked by default: the client has stacked packs since 1.20.3, nothing has to guess how two
     * plugins' files combine, and each pack is cached on its own so adding a plugin costs players
     * that plugin's download rather than all of them again.
     */
    public void mode(PackMode mode) {
        builder.mode(mode);
    }

    public PackMode mode() {
        return builder.mode();
    }

    /** The line shown with the request. Null leaves the client its own wording. */
    public void prompt(Component prompt) {
        this.prompt = prompt;
    }

    /**
     * How a built pack's file name becomes a URL.
     *
     * <p>Usually {@link PackServer#urlFor}, but a server owner with their own web server or a CDN
     * can point this anywhere — which is the whole reason serving and deciding are separate.
     */
    public void urls(UnaryOperator<String> urls) {
        this.urls = urls == null ? name -> "" : urls;
    }

    // ---------------------------------------------------------------------------- contributing

    /** Takes a plugin's assets. False means the source is not there; see {@link #problems()}. */
    public boolean contribute(PackContribution contribution) {
        return library.offer(contribution);
    }

    /** Takes one back, by {@link PackContribution#id()}. */
    public boolean withdraw(String id) {
        return library.withdraw(id);
    }

    /** Takes back everything one plugin offered — for a plugin being disabled. */
    public int withdrawAllFrom(String owner) {
        return library.withdrawAllFrom(owner);
    }

    /** Everything offered, in the order it is applied. */
    public List<PackContribution> contributions() {
        return library.all();
    }

    /** What was refused, and why the last build did not work. */
    public List<String> problems() {
        List<String> all = new java.util.ArrayList<>(library.problems());
        all.addAll(builder.problems());
        return List.copyOf(all);
    }

    // ---------------------------------------------------------------------------- building

    /**
     * Builds the pack from what has been contributed.
     *
     * <p>Blocking and IO-bound — call it off the main thread. A build that fails leaves the previous
     * one in place and still servable, because a server whose pack fails to rebuild should keep
     * handing out the pack it already had rather than none at all.
     */
    public Optional<PackBuild> rebuild() {
        Optional<PackBuild> built = builder.build(library, description);
        if (built.isEmpty()) {
            if (library.isEmpty()) {
                // Nothing offered is not a failure. It is a server with no custom assets, which is
                // most of them.
                current = null;
            }
            return built;
        }
        PackBuild build = built.get();
        if (current == null || !current.digest().equals(build.digest())) {
            log.info("Resource pack built from {} contribution(s): {} pack(s), {}, {}",
                    build.contributions(), build.parts().size(), build.readableSize(),
                    build.mode() == PackMode.STACKED ? "stacked" : "combined");
        }
        current = build;
        return built;
    }

    /** The pack as it stands, if there is one. */
    public Optional<PackBuild> current() {
        return Optional.ofNullable(current);
    }

    // ---------------------------------------------------------------------------- sending

    /**
     * Offers the pack to one player, if there is one and they do not already have it.
     *
     * <p>Quietly does nothing when there is no pack — sending one that does not exist gives a player
     * a download error for no reason — and when they were already sent this exact pack. A failed
     * download is offered again, because a flaky connection should not leave somebody without the
     * pack until they reconnect; a refusal is not, because that was their answer.
     */
    public void sendTo(UUID player) {
        PackBuild build = current;
        if (player == null || build == null || build.isEmpty()) {
            return;
        }
        String digest = build.digest();
        if (digest.equals(sentDigest.get(player)) && !statusOf(player).isWorthRetrying()) {
            return;
        }

        List<PackOffer> offers = new java.util.ArrayList<>(build.parts().size());
        for (PackPart part : build.parts()) {
            String url = urls.apply(part.fileName());
            if (url == null || url.isBlank()) {
                // Not the player's problem and not silently theirs to suffer: without a URL there is
                // nothing to send, and an owner needs to know the pack they built is going nowhere.
                log.warn("The resource pack is built but there is nowhere to download it from; "
                        + "nothing was sent. Start the pack server or set a URL.");
                return;
            }
            offers.add(new PackOffer(Hashes.packId(part.label(), part.sha1()), url, part.sha1(),
                    required, prompt));
        }

        sentDigest.put(player, digest);
        status.put(player, PackStatus.SENT);
        // All of them in one request: the client applies them in order, and sending them one at a
        // time would be one prompt per pack and one chance per pack for the order to come out wrong.
        sink.send(player, List.copyOf(offers));
    }

    /** Offers it to everybody given — after a rebuild, so nobody is left on the old one. */
    public void sendToAll(Collection<UUID> players) {
        if (players == null) {
            return;
        }
        players.forEach(this::sendTo);
    }

    /** Takes the pack back off a player. */
    public void clearFor(UUID player) {
        if (player == null) {
            return;
        }
        sentDigest.remove(player);
        status.remove(player);
        sink.clear(player);
    }

    /**
     * Forgets a player entirely — for one who has left.
     *
     * <p>Without this a player who declined, or whose client cleared its cache, would never be
     * offered the pack again for as long as the server was up.
     */
    public void forget(UUID player) {
        if (player != null) {
            sentDigest.remove(player);
            status.remove(player);
        }
    }

    // ---------------------------------------------------------------------------- outcomes

    /** What the client said. */
    public void record(UUID player, PackStatus what) {
        if (player == null || what == null) {
            return;
        }
        status.put(player, what);
        if (what == PackStatus.FAILED) {
            log.warn("{} could not download the resource pack.", player);
        }
    }

    /** Where a player is with it. */
    public PackStatus statusOf(UUID player) {
        return player == null ? PackStatus.NOT_SENT
                : status.getOrDefault(player, PackStatus.NOT_SENT);
    }

    /** Everybody who actually has the assets on screen — the only ones custom glyphs work for. */
    public Set<UUID> wearing() {
        return status.entrySet().stream()
                .filter(entry -> entry.getValue().isWearing())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Whether one player has it, for a plugin deciding whether to draw its custom icons. */
    public boolean isWearing(UUID player) {
        return statusOf(player).isWearing();
    }
}
