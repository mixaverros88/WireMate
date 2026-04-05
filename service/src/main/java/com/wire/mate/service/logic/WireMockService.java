package com.wire.mate.service.logic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wire.mate.service.config.JsonHelper;
import com.wire.mate.service.exception.WireMockNotFound;
import com.wire.mate.service.gateway.dto.WiremockRequest;
import com.wire.mate.service.gateway.WireMockGateway;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class WireMockService {

    private final WireMockGateway wireMockGateway;
    private final JsonHelper jsonHelper;
    private final ObjectMapper objectMapper;

    public WireMockService(
            WireMockGateway wireMockGateway,
            JsonHelper jsonHelper,
            ObjectMapper objectMapper
    ) {
        this.wireMockGateway = wireMockGateway;
        this.jsonHelper = jsonHelper;
        this.objectMapper = objectMapper;
    }

    public void publishStub(WiremockRequest mock) {
        String payload = jsonHelper.toJson(mock);
        wireMockGateway.createStub(payload, mock.id());
    }

    public void updateStub(WiremockRequest mock) {
        String payload = jsonHelper.toJson(mock);
        wireMockGateway.updateStub(payload, mock.id());
    }

    public void deleteStub(UUID mockId) throws WireMockNotFound {
        wireMockGateway.deleteStub(mockId);
    }

    public void getById(UUID mockId) throws WireMockNotFound {
        wireMockGateway.getById(mockId);
    }

    public void deleteByProjectName(UUID projectId) {
        ObjectNode criteria = objectMapper.createObjectNode();
        criteria.put("type", "EQUALTO");
        criteria.put("attribute", "metadata.projectId");
        criteria.put("value", projectId.toString());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("matchingType", "AND");
        body.set("criteria", objectMapper.createArrayNode().add(criteria));

        String json = jsonHelper.toJson(body);
        wireMockGateway.removeByMetadata(json);
    }

}
