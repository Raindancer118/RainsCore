package de.raindancer.core.ui.prompt;

import java.util.Optional;

/**
 * What a typed answer turned out to be: a value, or the reason it is not one — said to the player, so
 * it should say what would work ("a whole number from 1 to 64"), not what went wrong inside.
 */
public record Parsed<T>(T value, String problem) {

    public static <T> Parsed<T> ok(T value) {
        return new Parsed<>(value, null);
    }

    public static <T> Parsed<T> no(String problem) {
        return new Parsed<>(null, problem == null || problem.isBlank() ? "That is not something this takes." : problem);
    }

    public boolean isOk() {
        return problem == null;
    }

    public Optional<T> asOptional() {
        return isOk() ? Optional.ofNullable(value) : Optional.empty();
    }
}
