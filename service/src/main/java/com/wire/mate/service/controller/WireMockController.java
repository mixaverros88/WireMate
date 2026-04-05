package com.wire.mate.service.controller;

import com.wire.mate.service.service.MockService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Operational endpoints for re-publishing stubs to WireMock.
 *
 * <p>The previous version of this controller leaked the raw WireMock JSON
 * response back to API consumers, which made the public contract dependent on
 * the internal admin API. This rewrite returns {@code 204 No Content} on
 * success — callers should treat re-publish as a fire-and-confirm operation;
 * if it fails, the global handler maps it to {@code 502 Bad Gateway}.</p>
 *
 * <p>Prefer the canonical path {@code POST /api/mocks/{mockId}/republish}; the
 * {@code /api/wiremock/...} routes here are kept as backwards-compatible
 * aliases.</p>
 */
@RestController
@RequestMapping("/api/wiremock")
public class WireMockController {

    private final MockService mockService;

    public WireMockController(MockService mockService) {
        this.mockService = mockService;
    }

    @PostMapping("/mocks/{mockId}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publishMock(@PathVariable UUID mockId) {
        mockService.republish(mockId);
    }
}
