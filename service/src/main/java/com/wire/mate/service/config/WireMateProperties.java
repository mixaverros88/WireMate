package com.wire.mate.service.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Strongly-typed configuration root for the WireMate service.
 *
 * <p>Bound from {@code app.*} keys in {@code application.properties}. Validated
 * at startup via {@link Validated}, so misconfiguration fails fast instead of
 * surfacing as runtime NullPointerExceptions or hung HTTP calls.</p>
 */
@ConfigurationProperties(prefix = "app")
@Validated
public record WireMateProperties(

        @NotNull
        @Valid
        WireMock wiremock,

        @NotNull
        @Valid
        CrossCheck crossCheck,

        @NotNull
        @Valid
        Cors cors
) {

    public record WireMock(
            @NotBlank String baseUrl,
            @NotBlank String mainPath,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @Min(0) int maxRetries,
            @NotNull Duration retryBackoff
    ) {
    }

    public record CrossCheck(
            @NotNull Duration interval,
            boolean enabled
    ) {
    }

    public record Cors(
            String[] allowedOrigins
    ) {
    }
}
