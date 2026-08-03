/**
 * Vendored third-party code. Not ours, and not to be treated as ours.
 *
 * <h2>What this is</h2>
 * <a href="https://github.com/MrMicky-FR/FastBoard">FastBoard</a> 2.2.0 by MrMicky, MIT licensed,
 * copied in on 2026-08-03 and repackaged from {@code fr.mrmicky.fastboard}. It is a packet-level
 * scoreboard: it writes the scoreboard packets directly instead of going through Bukkit's
 * {@code Scoreboard} API, which is what makes it flicker-free, usable from any thread, and able to
 * coexist with other plugins that are also using teams.
 *
 * <h2>Why copied and not depended on</h2>
 * This project does not want external runtime dependencies. Paper's {@code libraries:} block would
 * have fetched it at startup, and shading was not an option anyway — maven-shade cannot read Java 25
 * class files. Copying it is what is left, and MIT expressly allows it as long as the copyright
 * notice stays, which is why every file here still carries MrMicky's header. See
 * {@code THIRD-PARTY.md} in the project root.
 *
 * <h2>Rules for this package</h2>
 * <ul>
 *   <li><b>Prefer changing the wrapper, not this.</b> This is a copy, not a fork: as long as the
 *       only difference from upstream is the package name, upgrading is unpack-and-rename. Every
 *       local edit turns that into a merge, which is how vendored code ends up years out of date.
 *       Adapting it is allowed where it genuinely has to be — it is our copy now — but the bar is
 *       "this cannot be done from outside", and anything done here goes in {@code THIRD-PARTY.md}
 *       so the next upgrade knows to re-apply it. As it stands the only change is the package.</li>
 *   <li><b>Nothing outside {@code core.scoreboard} may import it.</b> It is an implementation detail
 *       of how a scoreboard reaches a player, and swapping it out should cost one class.
 *       {@code NoExternalDependenciesTest} enforces this.</li>
 *   <li>The String-based {@code FastBoard} was deliberately left behind — everything here speaks
 *       Adventure components.</li>
 * </ul>
 *
 * <h2>How this reports trouble</h2>
 * It does not, and deliberately so. This code never logs; it throws — {@code RuntimeException} for a
 * packet it could not send, and {@link ExceptionInInitializerError} from a static block on a server
 * whose internals it does not recognise. Catching all of that and putting it through
 * {@link de.raindancer.core.platform.log.Log} is {@code Scoreboards}' job, which is the right place for it:
 * that class already knows whether the failure is worth a warning, a debug line, or switching
 * sidebars off for the rest of the session. Editing logging into these files would duplicate a
 * judgement that is already made one level up, and cost us the clean upgrade path for nothing.
 */
package de.raindancer.core.internal.fastboard;
