package de.raindancer.core.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a number is allowed to be.
 *
 * <p>Enforced in three places from this one declaration: the command refuses a value outside it,
 * the GUI clamps rather than letting a click walk past the end, and a file edited by hand is pulled
 * back inside the range when it is read. The default is checked against it when the schema is built,
 * so a bound that contradicts its own default fails at startup rather than on a server.
 *
 * <p>Bounds are whole numbers even for a decimal setting: every range these plugins actually need is
 * a whole number, and two kinds of bound would double the parsing for no reader's benefit.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD,
        ElementType.FIELD})
public @interface Range {
    int min() default Integer.MIN_VALUE;

    int max() default Integer.MAX_VALUE;
}
