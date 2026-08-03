package de.raindancer.core.data.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What this setting is called, in a person's words.
 *
 * <p>Optional. Left off, the component name is turned into a readable one — {@code fenceHeight}
 * becomes "Fence height" — which is right often enough to be worth having and wrong often enough
 * that most settings should say.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD,
        ElementType.FIELD})
public @interface Title {
    String value();
}
