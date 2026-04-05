package com.wire.mate.service.controller;

import com.wire.mate.service.dto.mock.CloneMockRequest;
import com.wire.mate.service.dto.mock.CreateMockRequest;
import com.wire.mate.service.dto.mock.ExistsByNameRequest;
import com.wire.mate.service.dto.mock.ExistsByUrlRequest;
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

    private final MockService service;

    public MockController(MockService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MockResponse create(@Valid @RequestBody CreateMockRequest request) {
        return service.create(request);
    }

    @PutMapping("/{mockId}")
    @ResponseStatus(HttpStatus.OK)
    public MockResponse update(
            @PathVariable UUID mockId,
            @Valid @RequestBody UpdateMockRequest request
    ) {
        return service.update(mockId, request);
    }

    @GetMapping("/{mockId}")
    @ResponseStatus(HttpStatus.OK)
    public MockResponse getById(@PathVariable UUID mockId) {
        return service.getById(mockId);
    }

    @PostMapping("/projects/{projectId}/exists/by-name")
    @ResponseStatus(HttpStatus.OK)
    public boolean existsByNameInProject(
            @PathVariable UUID projectId,
            @Valid @RequestBody ExistsByNameRequest request
    ) {
        return service.existsByNameInProject(projectId, request.name());
    }

    @PostMapping("/projects/{projectId}/exists/by-url")
    @ResponseStatus(HttpStatus.OK)
    public boolean existsByUrlInProject(
            @PathVariable UUID projectId,
            @Valid @RequestBody ExistsByUrlRequest request
    ) {
        return service.existsByUrlInProject(projectId, request.url());
    }

    @PostMapping("/{mockId}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public MockResponse clone(@PathVariable UUID mockId,
                              @Valid @RequestBody CloneMockRequest request) {
        return service.clone(mockId, request);
    }

    @PutMapping("/{mockId}/move")
    @ResponseStatus(HttpStatus.OK)
    public MockResponse move(@PathVariable UUID mockId,
                             @Valid @RequestBody MoveMockRequest request) {
        return service.move(mockId, request);
    }

    @PostMapping("/{mockId}/republish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void republish(@PathVariable UUID mockId) {
        service.republish(mockId);
    }

    @DeleteMapping("/{mockId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID mockId) {
        service.delete(mockId);
    }

}
