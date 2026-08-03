package de.raindancer.core.prompt;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Asking a player to type something, when more than one plugin might want to.
 *
 * <h2>Why this is shared</h2>
 * Three of these existed before it: one in this library's settings, one in the claims module, one in
 * the Hunger Games. Each registered its own chat listener and each swallowed the next line the
 * player typed. Installed together they fight — a player answering "what should this claim be
 * called?" has their answer eaten by whichever listener happened to run first, and the other plugin
 * waits for an answer that already went somewhere else.
 *
 * <p>It is the action bar problem in a different costume: one player, one next line of chat, several
 * plugins wanting it. Same answer — one owner, and plugins ask.
 *
 * <h2>Why a second asker is refused rather than replacing the first</h2>
 * Because replacing is the silent failure. The first plugin is left holding whatever it captured,
 * waiting for a callback that will never come, and the player answers a question they were no longer
 * being asked. Refusing means the second plugin finds out immediately and can say "one thing at a
 * time".
 *
 * <h2>Thread safety</h2>
 * Safe from any thread — chat arrives asynchronously. Claiming a player is one atomic
 * {@code putIfAbsent}, which is what makes "exactly one plugin wins" true rather than likely.
 */
public final class ChatPrompts {

    private static final LogChannel log = Log.of("prompt");

    /** What somebody types to call a question off. */
    private static final Set<String> CANCEL_WORDS = Set.of("cancel", "abbrechen", "stop", "nope");

    /** One question, waiting for a line. */
    private record Question(String owner, long expiresAt, Consumer<String> onAnswer,
                            Runnable onCancelled) {
    }

    private final LongSupplier clock;
    private final Map<UUID, Question> waiting = new ConcurrentHashMap<>();

    /** @param clock milliseconds; injected so expiry can be tested without waiting for it */
    public ChatPrompts(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Asks a player for a line of chat.
     *
     * @param owner       who is asking — a plugin or subsystem name, for diagnosing a stuck prompt
     * @param validFor    how long to wait before giving up
     * @param onAnswer    given the line they typed, on whatever thread chat arrived on
     * @param onCancelled run when they say cancel, when it expires, or when they log out; may be null
     * @return whether the question was asked. False means somebody else is already asking them
     *         something, and the caller should say so rather than wait.
     */
    public boolean ask(UUID player, String owner, Duration validFor, Consumer<String> onAnswer,
                       Runnable onCancelled) {
        if (player == null || onAnswer == null) {
            return false;
        }
        String who = owner == null ? "" : owner.trim();
        if (who.isEmpty() || validFor == null || validFor.isZero() || validFor.isNegative()) {
            return false;
        }
        // Expired questions do not block a new one, so a plugin that died mid-prompt cannot lock
        // somebody out of being asked anything ever again.
        expireIfDue(player);

        Question question = new Question(who, clock.getAsLong() + validFor.toMillis(),
                onAnswer, onCancelled);
        return waiting.putIfAbsent(player, question) == null;
    }

    /**
     * Offers a line of chat to whoever is waiting for one.
     *
     * <p>Called by the one chat listener. {@link PromptResult#NOT_WAITING} means the line was not
     * ours and must reach chat as normal — anything else means it was consumed.
     */
    public PromptResult offer(UUID player, String line) {
        if (player == null || line == null) {
            return PromptResult.NOT_WAITING;
        }
        expireIfDue(player);
        Question question = waiting.remove(player);
        if (question == null) {
            return PromptResult.NOT_WAITING;
        }
        if (CANCEL_WORDS.contains(line.trim().toLowerCase(Locale.ROOT))) {
            run(question.onCancelled(), question.owner());
            return PromptResult.CANCELLED;
        }
        try {
            question.onAnswer().accept(line);
            return PromptResult.ANSWERED;
        } catch (RuntimeException failure) {
            // The question is over either way: a plugin's bug must not leave somebody unable to use
            // chat until they log out.
            log.error(failure, "The '{}' prompt threw on an answer from {}.",
                    question.owner(), player);
            return PromptResult.FAILED;
        }
    }

    /** Whether anybody is waiting on this player. */
    public boolean isWaiting(UUID player) {
        if (player == null) {
            return false;
        }
        expireIfDue(player);
        return waiting.containsKey(player);
    }

    /** Who is asking them, for diagnosing a prompt that seems stuck. */
    public Optional<String> waitingFor(UUID player) {
        if (player == null) {
            return Optional.empty();
        }
        expireIfDue(player);
        return Optional.ofNullable(waiting.get(player)).map(Question::owner);
    }

    /**
     * Takes back a question.
     *
     * @return false when they were not being asked, or were being asked by somebody else — a plugin
     *         must not be able to cancel another's question
     */
    public boolean withdraw(UUID player, String owner) {
        if (player == null || owner == null) {
            return false;
        }
        Question question = waiting.get(player);
        if (question == null || !question.owner().equals(owner.trim())) {
            return false;
        }
        return waiting.remove(player, question);
    }

    /** Forgets a player and tells whoever was asking. Called when they log out. */
    public void forget(UUID player) {
        if (player == null) {
            return;
        }
        Question question = waiting.remove(player);
        if (question != null) {
            run(question.onCancelled(), question.owner());
        }
    }

    /**
     * Drops questions nobody answered, telling whoever asked.
     *
     * <p>Called on a slow timer. The telling is the point: a plugin waiting on an answer holds
     * whatever it captured — a half-built claim, a menu it means to reopen — and needs to know none
     * is coming.
     */
    public void sweep() {
        long now = clock.getAsLong();
        for (UUID player : Set.copyOf(waiting.keySet())) {
            Question question = waiting.get(player);
            if (question != null && now >= question.expiresAt()
                    && waiting.remove(player, question)) {
                run(question.onCancelled(), question.owner());
            }
        }
    }

    /** Who is being asked something, for a diagnostic command. */
    public List<String> pending() {
        return waiting.values().stream().map(Question::owner).toList();
    }

    /**
     * Drops this player's question if its time is up.
     *
     * <p>Quietly: {@link #sweep} is what tells the asker. This only stops an expired question
     * standing in the way of a new one, which it does on every read so a slow sweep cannot leave
     * somebody blocked.
     */
    private void expireIfDue(UUID player) {
        Question question = waiting.get(player);
        if (question != null && clock.getAsLong() >= question.expiresAt()) {
            waiting.remove(player, question);
        }
    }

    private static void run(Runnable maybe, String owner) {
        if (maybe == null) {
            return;
        }
        try {
            maybe.run();
        } catch (RuntimeException failure) {
            log.error(failure, "The '{}' prompt threw while being cancelled.", owner);
        }
    }
}
