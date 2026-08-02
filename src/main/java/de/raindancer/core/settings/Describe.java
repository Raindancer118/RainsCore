package de.raindancer.core.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What this setting does, phrased for a server owner rather than for a developer.
 *
 * <p>Becomes the lore under the button, the comment above the key in {@code config.yml} and the
 * line {@code /… help} prints. Left off, all three simply have nothing there — an invented
 * description is worse than none.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD,
        ElementType.FIELD})
public @interface Describe {
    String value();
}
