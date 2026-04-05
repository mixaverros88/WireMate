package com.wire.mate.service.logic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wire.mate.service.entity.Mock;
import com.wire.mate.service.exception.MockNotFoundException;
import com.wire.mate.service.exception.WireMockPublishException;
import com.wire.mate.service.gateway.WireMockGateway;
import com.wire.mate.service.repository.MockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Encapsulates the "publish to WireMock" use cases.
 *
 * <p>Read methods use {@code @Transactional(readOnly = true)} only for the entity
 * lookup; the actual HTTP call happens after the transactional boundary so we
 * never hold a database connection while waiting on a remote service.</p>
 *
 * <p>Reserved metadata keys ({@code generatedBy}, {@code projectName},
 * {@code projectId}) are always written by this layer at publish time so that
 * even if a stale value sneaked into the database it cannot override the
 * server-owned values.</p>
 */
@Service
public class WireMockService {

    private static final String META_GENERATED_BY = "generatedBy";
    private static final String META_PROJECT_NAME = "projectName";
    private static final String META_PROJECT_ID = "projectId";
    private static final String GENERATED_BY_VALUE = "wiremate";

    private final MockRepository mockRepository;
    private final WireMockGateway wireMockGateway;
    private final ObjectMapper objectMapper;

    public WireMockService(MockRepository mockRepository,
                           WireMockGateway wireMockGateway,
                           ObjectMapper objectMapper) {
        this.mockRepository = mockRepository;
        this.wireMockGateway = wireMockGateway;
        this.objectMapper = objectMapper;
    }

    /**
     * Publish (create or replace) a stub in WireMock for the given mock.
     *
     * <p>The DB read runs inside a short read-only transaction. The actual HTTP
     * call to WireMock is then performed without a transaction so the database
     * connection is returned to the pool while the network call happens.</p>
     */
    public Map<String, Object> publishMock(UUID mockId) {
        PublishPayload payload = loadPublishPayload(mockId);
        return wireMockGateway.createMapping(payload.json(), payload.mockName());
    }

    /**
     * Update an existing stub. The gateway falls back to create-on-404, so this
     * is safe to call when the in-memory WireMock has been restarted.
     */
    public Map<String, Object> updateMock(UUID mockId) {
        PublishPayload payload = loadPublishPayload(mockId);
        return wireMockGateway.updateMapping(payload.json(), payload.mockName(), mockId);
    }

    /**
     * Bulk-delete every stub in WireMock that belongs to the given project,
     * matched by the {@code metadata.projectName} field that {@link #buildMetadata}
     * always writes.
     */
    public void deleteByProjectName(String projectName) {
        ObjectNode criteria = objectMapper.createObjectNode();
        criteria.put("type", "EQUALTO");
        criteria.put("attribute", "metadata." + META_PROJECT_NAME);
        criteria.put("value", projectName);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("matchingType", "AND");
        body.set("criteria", objectMapper.createArrayNode().add(criteria));

        try {
            String json = objectMapper.writeValueAsString(body);
            wireMockGateway.removeByMetadata(json);
        } catch (JsonProcessingException e) {
            throw new WireMockPublishException(
                    "Failed to serialize delete-by-project request for '" + projectName + "'", e);
        }
    }

    public void deleteStub(UUID mockId) {
        wireMockGateway.deleteMapping(mockId.toString());
    }

    @Transactional(readOnly = true)
    protected PublishPayload loadPublishPayload(UUID mockId) {
        Mock mock = mockRepository.findById(mockId)
                .orElseThrow(() -> new MockNotFoundException(mockId));

        Map<String, Object> mapping = buildMapping(mock, mockId);
        try {
            return new PublishPayload(objectMapper.writeValueAsString(mapping), mock.getName());
        } catch (JsonProcessingException e) {
            throw new WireMockPublishException("Failed to serialize mock mapping: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildMapping(Mock mock, UUID mockId) {
        Map<String, Object> mapping = new HashMap<>();
        mapping.put("id", mockId);
        mapping.put("request", mock.getRequestDefinition());
        mapping.put("name", mock.getName());
        mapping.put("response", mock.getResponseDefinition());
        mapping.put("serveEventListeners", mock.getServeEventListeners());
        mapping.put("persistent", mock.isPersistent());
        mapping.put("priority", mock.getPriority());
        mapping.put("metadata", buildMetadata(mock));
        return mapping;
    }

    /**
     * Merge caller-supplied metadata with the values WireMate owns.
     *
     * <p>The reserved keys ({@code generatedBy}, {@code projectName},
     * {@code projectId}) are written last so even if the database somehow
     * contained a stored value for them the system value wins when published
     * to WireMock.</p>
     */
    private Map<String, Object> buildMetadata(Mock mock) {
        Map<String, Object> metadata = new HashMap<>();
        if (mock.getMetadata() != null) {
            metadata.putAll(mock.getMetadata());
        }
        metadata.put(META_GENERATED_BY, GENERATED_BY_VALUE);
        metadata.put(META_PROJECT_NAME, mock.getProject().getName());
        metadata.put(META_PROJECT_ID, mock.getProject().getId());
        return metadata;
    }

    /** Internal container for a serialized publish request. */
    record PublishPayload(String json, String mockName) {
    }
}
