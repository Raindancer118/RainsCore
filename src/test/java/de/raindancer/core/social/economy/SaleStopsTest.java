package de.raindancer.core.social.economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SaleStopsTest {

    private final org.bukkit.plugin.Plugin owner = mock(org.bukkit.plugin.Plugin.class);

    @AfterEach
    void reset() {
        SaleStops.clear();
    }

    @Test
    @DisplayName("with nobody stopping sales everything is for sale")
    void nobody() {
        assertThat(SaleStops.reason("COD")).isEmpty();
    }

    @Test
    @DisplayName("a stop names what it stops and why; names are matched as upper case")
    void stops() {
        SaleStop goals = material -> material.equals("COD") ? Optional.of("The server is collecting it") : Optional.empty();
        SaleStops.provide(owner, goals);
        assertThat(SaleStops.reason("cod")).contains("The server is collecting it");
        assertThat(SaleStops.reason("STONE")).isEmpty();
        SaleStops.retract(goals);
        assertThat(SaleStops.reason("COD")).isEmpty();
    }

    @Test
    @DisplayName("a stop that throws or answers null stops nothing; the next one still counts")
    void broken() {
        SaleStops.provide(owner, material -> {
            throw new IllegalStateException("loading");
        });
        SaleStops.provide(owner, material -> null);
        SaleStops.provide(owner, material -> Optional.of("held"));
        assertThat(SaleStops.reason("COD")).contains("held");
        assertThat(SaleStops.reason(null)).isEmpty();
    }

    @Test
    @DisplayName("what a disabled plugin's code provided is forgotten")
    void forgetFrom() {
        SaleStop mine = material -> Optional.of("held");
        SaleStops.provide(owner, mine);
        SaleStops.provide(owner, mine);
        assertThat(SaleStops.forgetFrom(mine.getClass().getClassLoader())).isEqualTo(1);
        assertThat(SaleStops.reason("COD")).isEmpty();
    }
}
