package com.wire.mate.service.controller;

import com.wire.mate.service.dto.mock.CloneMockRequest;
import com.wire.mate.service.dto.mock.CreateMockRequest;
import com.wire.mate.service.dto.mock.MockResponse;
import com.wire.mate.service.dto.mock.MoveMockRequest;
import com.wire.mate.service.dto.mock.UpdateMockRequest;
import com.wire.mate.service.service.MockService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/mocks")
public class MockController {

    private final MockService mockService;

    public MockController(MockService mockService) {
        this.mockService = mockService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MockResponse createMock(@Valid @RequestBody CreateMockRequest request) {
        return mockService.createMock(request);
    }

    @PutMapping("/{mockId}")
    @ResponseStatus(HttpStatus.OK)
    public MockResponse updateMock(@PathVariable UUID mockId,
                                   @Valid @RequestBody UpdateMockRequest request) {
        return mockService.updateMock(mockId, request);
    }

    @GetMapping("/{mockId}")
    @ResponseStatus(HttpStatus.OK)
    public MockResponse getMockById(@PathVariable UUID mockId) {
        return mockService.getMockById(mockId);
    }

    @PostMapping("/{mockId}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public MockResponse cloneMock(@PathVariable UUID mockId,
                                  @Valid @RequestBody CloneMockRequest request) {
        return mockService.cloneMock(mockId, request);
    }

    @PutMapping("/{mockId}/move")
    @ResponseStatus(HttpStatus.OK)
    public MockResponse moveMock(@PathVariable UUID mockId,
                                 @Valid @RequestBody MoveMockRequest request) {
        return mockService.moveMock(mockId, request);
    }

    @PostMapping("/{mockId}/republish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void republishMock(@PathVariable UUID mockId) {
        mockService.republish(mockId);
    }

    @DeleteMapping("/{mockId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMock(@PathVariable UUID mockId) {
        mockService.deleteMock(mockId);
    }
}
