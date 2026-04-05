package com.wire.mate.service.gateway;

import com.wire.mate.service.config.WireMateProperties;
import com.wire.mate.service.exception.WireMockPublishException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Thin wrapper around the WireMock Admin API.
 *
 * <p>Responsibilities are intentionally narrow:</p>
 * <ul>
 *   <li>HTTP plumbing only — no domain logic, no entity awareness</li>
 *   <li>Translate transport errors into {@link WireMockPublishException}</li>
 *   <li>Apply timeouts (configured on the {@code RestClient}) and a small,
 *       capped retry with backoff for transient I/O failures</li>
 * </ul>
 */
@Component
public class WireMockGateway {

    private static final Logger log = LoggerFactory.getLogger(WireMockGateway.class);

    private final RestClient wiremockRestClient;
    private final String mainPath;
    private final int maxRetries;
    private final long retryBackoffMillis;

    public WireMockGateway(RestClient wiremockRestClient, WireMateProperties properties) {
        this.wiremockRestClient = wiremockRestClient;
        WireMateProperties.WireMock cfg = properties.wiremock();
        this.mainPath = cfg.mainPath();
        this.maxRetries = cfg.maxRetries();
        this.retryBackoffMillis = cfg.retryBackoff().toMillis();
    }

    /**
     * Create a new mapping. If WireMock returns a 4xx/5xx the failure surfaces
     * as a {@link WireMockPublishException} — those are not retried because
     * client errors won't fix themselves.
     */
    public Map<String, Object> createMapping(String mapping, String mockName) {
        return withRetry("createMapping[" + mockName + "]", () ->
                exchangeForMap(HttpMethod.POST, mainPath, mapping, mockName));
    }

    /**
     * Update an existing mapping. WireMock returns 404 if the stub is gone (for
     * example after a WireMock restart) — in that case we fall back to a create
     * so the caller's intent (make this stub exist) is satisfied either way.
     */
    public Map<String, Object> updateMapping(String mapping, String mockName, UUID mockId) {
        try {
            return withRetry("updateMapping[" + mockName + "]", () ->
                    exchangeForMapWithUriVariables(
                            HttpMethod.PUT, mainPath + "/{id}", mapping, mockName, mockId.toString()));
        } catch (WireMockPublishException ex) {
            // Translate the "stub not found" case into a create so callers don't
            // have to know about the recovery path.
            Throwable cause = ex.getCause();
            if (cause instanceof HttpClientErrorException.NotFound) {
                log.info("Stub {} not found in WireMock during update; falling back to create.", mockId);
                return createMapping(mapping, mockName);
            }
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    public Set<String> getAllMappingIds() {
        return withRetry("getAllMappingIds", () -> {
            try {
                Map<String, Object> response = wiremockRestClient
                        .get()
                        .uri(mainPath)
                        .retrieve()
                        .body(Map.class);

                if (response == null) {
                    return Collections.emptySet();
                }
                Object mappingsObj = response.get("mappings");
                if (!(mappingsObj instanceof List<?> mappings) || mappings.isEmpty()) {
                    return Collections.emptySet();
                }

                return mappings.stream()
                        .map(m -> ((Map<String, Object>) m).get("id"))
                        .filter(java.util.Objects::nonNull)
                        .map(Object::toString)
                        .collect(Collectors.toSet());
            } catch (RestClientException ex) {
                throw new WireMockPublishException("Failed to fetch mappings from WireMock", ex);
            }
        });
    }

    public void removeByMetadata(String metadata) {
        withRetry("removeByMetadata", () -> {
            try {
                wiremockRestClient
                        .method(HttpMethod.DELETE)
                        .uri(mainPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(metadata)
                        .retrieve()
                        .toBodilessEntity();
                return null;
            } catch (RestClientException ex) {
                throw new WireMockPublishException("Failed to remove WireMock mappings by metadata", ex);
            }
        });
    }

    public void deleteMapping(String mappingId) {
        withRetry("deleteMapping[" + mappingId + "]", () -> {
            try {
                wiremockRestClient
                        .delete()
                        .uri(mainPath + "/{id}", mappingId)
                        .retrieve()
                        .toBodilessEntity();
                return null;
            } catch (HttpClientErrorException.NotFound ignored) {
                // Mapping no longer exists in WireMock — treat as already deleted.
                return null;
            } catch (RestClientException ex) {
                throw new WireMockPublishException(
                        "Failed to delete WireMock mapping '" + mappingId + "'", ex);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeForMap(HttpMethod method, String uri, String body, String mockName) {
        try {
            return wiremockRestClient
                    .method(method)
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException ex) {
            throw new WireMockPublishException(
                    "Failed to publish mock '" + mockName + "' to WireMock", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeForMapWithUriVariables(
            HttpMethod method, String uri, String body, String mockName, String... uriVars) {
        try {
            return wiremockRestClient
                    .method(method)
                    .uri(uri, (Object[]) uriVars)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException ex) {
            throw new WireMockPublishException(
                    "Failed to publish mock '" + mockName + "' to WireMock", ex);
        }
    }

    /**
     * Run a call with bounded retries. Only retries on transient failures
     * (i.e. {@link WireMockPublishException} caused by a non-{@link HttpClientErrorException}
     * — 4xx errors won't get better by retrying). Sleeps a fixed backoff
     * between attempts so the WireMock host is not hammered during partial
     * outages.
     */
    private <T> T withRetry(String operation, Callable<T> call) {
        WireMockPublishException last = null;
        int attempts = maxRetries + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return call.call();
            } catch (WireMockPublishException ex) {
                last = ex;
                if (!isRetryable(ex) || attempt == attempts) {
                    throw ex;
                }
                log.warn("Retrying {} after transient failure (attempt {}/{}): {}",
                        operation, attempt, attempts, ex.getMessage());
                sleepBackoff();
            } catch (Exception ex) {
                throw new WireMockPublishException(operation + " failed", ex);
            }
        }
        // Defensive — loop above either returns or throws.
        throw last == null ? new WireMockPublishException(operation + " failed") : last;
    }

    private static boolean isRetryable(WireMockPublishException ex) {
        return !(ex.getCause() instanceof HttpClientErrorException);
    }

    private void sleepBackoff() {
        if (retryBackoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(retryBackoffMillis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
