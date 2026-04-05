package com.wire.mate.service.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Propagates a correlation ID across the request lifecycle.
 *
 * <p>Reads {@code X-Correlation-Id} (or {@code X-Request-Id}) from the inbound
 * request when present, generates one otherwise, mirrors it back on the response
 * and stores it on {@link MDC} so every log line for this request carries it.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String correlationId = firstNonBlank(
                request.getHeader(HEADER_CORRELATION_ID),
                request.getHeader(HEADER_REQUEST_ID),
                UUID.randomUUID().toString()
        );

        try {
            MDC.put(MDC_KEY, correlationId);
            response.setHeader(HEADER_CORRELATION_ID, correlationId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String value : candidates) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        // Unreachable in practice — the last candidate is always a generated UUID.
        return UUID.randomUUID().toString();
    }
}
