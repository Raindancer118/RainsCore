package de.raindancer.core.world.speedrun;

/**
 * A way a run can end: an advancement, a death, or anything else somebody can write a Bukkit
 * listener for.
 *
 * <h2>Why not {@link de.raindancer.core.platform.rule.IRule}</h2>
 * A rule judges something handed to it, synchronously, with no side effects — the right shape for
 * "may this claim be made". Ending a run is the opposite: nothing asks it a question, it watches the
 * server on its own and reaches out to {@link SpeedrunSession#finish} whenever it decides the moment
 * has come. It reuses {@code IRule}'s {@link #describe()}-from-class-name default because the reason
 * is the same one — a lambda-shaped condition should still say something better than
 * {@code "Foo$$Lambda"} in a log line — but it does not extend it; the two interfaces answer
 * different questions.
 *
 * <h2>Lifecycle</h2>
 * {@link #arm} is called exactly once, by {@link SpeedrunSession#addEndCondition}, and hooks the
 * condition up to whatever it watches — typically {@code Bukkit.getPluginManager().registerEvents}.
 * {@link #disarm} is called exactly once, when the session finishes, and must undo exactly that. A
 * condition that is armed twice or disarmed twice (because a caller called {@code addEndCondition}
 * more than once, say) is a caller error this interface does not have to tolerate gracefully.
 */
public interface SpeedrunEndCondition {

    /** Hooks itself up, watching {@code session} for whatever this condition ends a run on. */
    void arm(SpeedrunSession session);

    /** Detaches whatever {@link #arm} attached. Called once, when the run finishes. */
    void disarm();

    /** A name for the log line and {@link SpeedrunOutcome#reason()} — see {@code IRule.describe()}. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
