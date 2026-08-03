package de.raindancer.core.prompt;

/**
 * What became of a line of chat offered to the prompts.
 *
 * <p>{@link #NOT_WAITING} is the common case and the important one: it means the line was not ours
 * and must reach chat as normal. Everything else means the line was consumed and the chat event
 * should be cancelled.
 */
public enum PromptResult {

    /** Nobody was asking this player anything. Let it through. */
    NOT_WAITING,

    /** It was an answer, and whoever asked has had it. */
    ANSWERED,

    /** They said cancel. Whoever asked has been told the question is off. */
    CANCELLED,

    /** It was an answer and the plugin's handler threw. The question is over either way. */
    FAILED;

    /** Whether the line was ours, and so must not also appear in chat. */
    public boolean wasConsumed() {
        return this != NOT_WAITING;
    }
}
