package de.raindancer.core.data.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record as one plugin's settings.
 *
 * <p>The record is the single source: {@code config.yml}, its comments, the validation, the tab
 * completion and every settings screen are derived from it. There is no second list to keep in step
 * with it, which is the mistake the catalogue this replaces was built around — it shipped a
 * {@code config.yml} beside the catalogue and needed a build-failing test to keep the two honest.
 *
 * @see SettingsSchema
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Settings {

    /** The plugin this belongs to, e.g. {@code "claims"}. Names its section of the combined GUI. */
    String id();

    /**
     * The topics its settings are filed under.
     *
     * <p>Only subtopics of the three standard roots — see {@link SettingsTopics}. A plugin that
     * declares a root of its own is refused: one plugin inventing "Miscellaneous" is how the menu
     * became a wall of unrelated buttons in the first place.
     */
    Topic[] topics() default {};
}
