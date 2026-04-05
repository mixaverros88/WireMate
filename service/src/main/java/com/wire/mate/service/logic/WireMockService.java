package com.wire.mate.service.logic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wire.mate.service.config.JsonHelper;
import com.wire.mate.service.exception.WireMockNotFoundExe;
import com.wire.mate.service.gateway.dto.WiremockReq;
import com.wire.mate.service.gateway.WireMockGateway;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class WireMockService {

    private static final Logger log = LoggerFactory.getLogger(WireMockService.class);

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

    public void publishStub(WiremockReq mockReq) {
        String payload = jsonHelper.toJson(mockReq);
        log.debug("publishStub: payload={}", payload);
        wireMockGateway.publishStub(payload, mockReq.id());
    }

    public void updateStub(WiremockReq mock) {
        String payload = jsonHelper.toJson(mock);
        log.debug("updateStub: payload={}", payload);
        wireMockGateway.updateStub(payload, mock.id());
    }

    public void deleteStub(UUID mockId) throws WireMockNotFoundExe {
        log.debug("deleteStub: mockId={}", mockId);
        wireMockGateway.deleteStub(mockId);
    }

    public void getById(UUID mockId) throws WireMockNotFoundExe {
        wireMockGateway.getById(mockId);
    }

    public void deleteByProjectName(UUID projectId) {
        String json = prepareRemoveByProjectIdMetadata(projectId);
        log.debug("deleteByProjectName: metadata={}", json);
        wireMockGateway.removeByMetadata(json);
    }

    private @NonNull String prepareRemoveByProjectIdMetadata(UUID projectId) {
        ObjectNode matcher = objectMapper.createObjectNode();
        matcher.put("expression", "$.projectId");
        matcher.put("equalTo", projectId.toString());

        ObjectNode body = objectMapper.createObjectNode();
        body.set("matchesJsonPath", matcher);

        return jsonHelper.toJson(body);
    }

}
