package com.wire.mate.service.gateway;

import com.wire.mate.service.config.WireMateProperties;
import com.wire.mate.service.exception.WireMockNotFound;
import com.wire.mate.service.exception.WireMockPublishExc;
import com.wire.mate.service.gateway.dto.MappingDto;
import com.wire.mate.service.gateway.dto.WireMockResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class WireMockGateway {

    private static final Logger log = LoggerFactory.getLogger(WireMockGateway.class);

    private final RestClient wiremockRestClient;
    private final String mainPath;

    public WireMockGateway(
            RestClient wiremockRestClient,
            WireMateProperties properties
    ) {
        this.wiremockRestClient = wiremockRestClient;
        WireMateProperties.WireMock cfg = properties.wiremock();
        this.mainPath = cfg.mainPath();
    }

    public void createStub(final String payload, final UUID stubId) {
        try {
            wiremockRestClient
                    .method(HttpMethod.POST)
                    .uri(mainPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException ex) {
            throw new WireMockPublishExc("Failed to publish stub id: '" + stubId + "' to WireMock", ex);
        }
    }

    public void updateStub(final String payload, final UUID stubId) {
        try {
            wiremockRestClient
                    .method(HttpMethod.PUT)
                    .uri(mainPath+ "/" + stubId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException.NotFound ex) {
            log.info("Stub {} not found in WireMock during update; falling back to create.", stubId);
            createStub(payload, stubId);
        } catch (RestClientException ex) {
            throw new WireMockPublishExc("Failed to update stub id: '" + stubId + "' to WireMock", ex);
        }
    }

    public WireMockResponseDto getAllStubs() {
        try {
            return wiremockRestClient
                    .method(HttpMethod.GET)
                    .uri(mainPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(WireMockResponseDto.class);
        } catch (RestClientException ex) {
            throw new WireMockPublishExc("dsad", ex);
        }
    }

    public MappingDto getById(final UUID stubId) throws WireMockNotFound {
        try {
            return wiremockRestClient
                    .method(HttpMethod.GET)
                    .uri(mainPath + "/" + stubId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(
                            status -> status.value() == 404,
                            (request, response) -> {
                                new WireMockNotFound();
                            }
                    )
                    .body(MappingDto.class);
        } catch (RestClientException ex) {
            throw new WireMockPublishExc("dsad", ex);
        }
    }

    public Set<String> getAllMappingIds() {
        return getAllStubs().mappings().stream().map(MappingDto::id).collect(Collectors.toSet());
    }

    public void removeByMetadata(final String metadata) {
        try {
            wiremockRestClient
                    .method(HttpMethod.DELETE)
                    .uri(mainPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(metadata)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw new WireMockPublishExc("Failed to remove WireMock mappings by metadata", ex);
        }
    }

    public void deleteStub(UUID stubId) throws WireMockNotFound {
            try {
                wiremockRestClient
                        .method(HttpMethod.DELETE)
                        .uri(mainPath+ "/" + stubId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .onStatus(
                                status -> status.value() == 404,
                                (request, response) -> {
                                    new WireMockNotFound();
                                }
                        )
                        .body(Map.class);
            } catch (RestClientException ex) {
                throw new WireMockPublishExc("Failed to delete WireMock Stub id: '" + stubId + "' to WireMock", ex);
            }
    }

}
