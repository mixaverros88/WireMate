package com.wire.mate.service.service;

import com.wire.mate.service.dto.mock.CloneMockRequest;
import com.wire.mate.service.dto.mock.CreateMockRequest;
import com.wire.mate.service.dto.mock.MockResponse;
import com.wire.mate.service.dto.mock.MoveMockRequest;
import com.wire.mate.service.dto.mock.UpdateMockRequest;
import com.wire.mate.service.entity.Mock;
import com.wire.mate.service.entity.Project;
import com.wire.mate.service.exception.MockAlreadyExistsException;
import com.wire.mate.service.exception.MockNotFoundException;
import com.wire.mate.service.exception.ProjectNotFoundException;
import com.wire.mate.service.logic.WireMockService;
import com.wire.mate.service.repository.MockRepository;
import com.wire.mate.service.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MockService {

    /**
     * Metadata keys that are owned by WireMate and must not be set by the caller.
     * They are always injected by {@link WireMockService} at publish time, so any
     * value submitted in {@link CreateMockRequest} / {@link UpdateMockRequest}
     * is silently stripped before persistence.
     */
    static final Set<String> RESERVED_METADATA_KEYS = Set.of("generatedBy", "projectName", "projectId");

    private final MockRepository mockRepository;
    private final ProjectRepository projectRepository;
    private final WireMockService wireMockService;

    public MockService(MockRepository mockRepository,
                       ProjectRepository projectRepository,
                       WireMockService wireMockService) {
        this.mockRepository = mockRepository;
        this.projectRepository = projectRepository;
        this.wireMockService = wireMockService;
    }

    @Transactional
    public MockResponse createMock(CreateMockRequest request) {
        Project project = projectRepository
                .findById(request.projectId())
                .orElseThrow(() -> new ProjectNotFoundException(request.projectId()));

        if (mockRepository.existsByNameAndProjectId(request.name(), request.projectId())) {
            throw new MockAlreadyExistsException(request.name(), project.getName());
        }

        Mock saved = mockRepository.save(request.toEntity(project, sanitizeMetadata(request.metadata())));
        publishAfterCommit(saved.getId());
        return saved.toDto();
    }

    @Transactional
    public MockResponse updateMock(UUID mockId, UpdateMockRequest request) {
        Mock mock = mockRepository
                .findById(mockId)
                .orElseThrow(() -> new MockNotFoundException(mockId));

        if (!mock.getName().equals(request.name())
                && mockRepository.existsByNameAndProjectId(request.name(), mock.getProject().getId())) {
            throw new MockAlreadyExistsException(request.name(), mock.getProject().getName());
        }

        mock.setName(request.name());
        mock.setDescription(request.description());
        mock.setRequestDefinition(request.request());
        mock.setResponseDefinition(request.response());
        mock.setPersistent(request.persistent());
        mock.setPriority(request.priority());
        mock.setMetadata(sanitizeMetadata(request.metadata()));
        mock.setServeEventListeners(request.serveEventListeners());
        mock.setCurl(request.curl());

        Mock saved = mockRepository.save(mock);
        publishUpdateAfterCommit(saved.getId());
        return saved.toDto();
    }

    @Transactional
    public MockResponse cloneMock(UUID mockId, CloneMockRequest request) {
        Mock sourceMock = mockRepository.findById(mockId)
                .orElseThrow(() -> new MockNotFoundException(mockId));

        Project project = sourceMock.getProject();

        if (mockRepository.existsByNameAndProjectId(request.name(), project.getId())) {
            throw new MockAlreadyExistsException(request.name(), project.getName());
        }

        Mock saved = mockRepository.save(new Mock(
                request.name(),
                sourceMock.getDescription(),
                project,
                sourceMock.getRequestDefinition(),
                sourceMock.getResponseDefinition(),
                sourceMock.isPersistent(),
                sourceMock.getPriority(),
                sourceMock.getMetadata(),
                sourceMock.getServeEventListeners(),
                sourceMock.getCurl()
        ));

        publishAfterCommit(saved.getId());
        return saved.toDto();
    }

    @Transactional
    public MockResponse moveMock(UUID mockId, MoveMockRequest request) {
        Mock mock = mockRepository.findById(mockId)
                .orElseThrow(() -> new MockNotFoundException(mockId));

        Project targetProject = projectRepository.findById(request.targetProjectId())
                .orElseThrow(() -> new ProjectNotFoundException(request.targetProjectId()));

        if (mockRepository.existsByNameAndProjectId(mock.getName(), targetProject.getId())) {
            throw new MockAlreadyExistsException(mock.getName(), targetProject.getName());
        }

        mock.setProject(targetProject);
        Mock saved = mockRepository.save(mock);
        publishAfterCommit(saved.getId());
        return saved.toDto();
    }

    @Transactional(readOnly = true)
    public MockResponse getMockById(UUID mockId) {
        return mockRepository
                .findById(mockId)
                .orElseThrow(() -> new MockNotFoundException(mockId))
                .toDto();
    }

    @Transactional(readOnly = true)
    public List<MockResponse> getMocksByProjectId(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ProjectNotFoundException(projectId);
        }
        return mockRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(Mock::toDto)
                .toList();
    }

    @Transactional
    public void deleteMock(UUID mockId) {
        if (!mockRepository.existsById(mockId)) {
            throw new MockNotFoundException(mockId);
        }
        mockRepository.deleteById(mockId);
        // Defer the WireMock cleanup until after the DB commit; if the HTTP
        // call fails, the cross-check task will detect the orphan and notify.
        UUID id = mockId;
        runAfterCommit(() -> wireMockService.deleteStub(id));
    }

    /**
     * Republish an existing mock to WireMock without touching the database.
     * Used by the operational re-publish endpoint after WireMock has been
     * restarted from scratch.
     */
    public void republish(UUID mockId) {
        // No DB write — but verify the row exists so we get a 404 instead of a
        // confusing 502 if the caller passes a bad id.
        if (!mockRepository.existsById(mockId)) {
            throw new MockNotFoundException(mockId);
        }
        wireMockService.publishMock(mockId);
    }

    /**
     * Strip {@link #RESERVED_METADATA_KEYS} a caller might try to submit. Those
     * keys are owned by WireMate and injected later in {@link WireMockService}
     * — allowing callers to set them would let them forge the
     * {@code generatedBy} / {@code projectName} values used for stub
     * bookkeeping and the delete-by-metadata flow. Returns an empty map if the
     * caller sent {@code null}, so the {@code NOT NULL} column constraint is
     * always satisfied.
     */
    private static Map<String, Object> sanitizeMetadata(Map<String, Object> submitted) {
        if (submitted == null || submitted.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, Object> sanitized = new HashMap<>(submitted);
        sanitized.keySet().removeAll(RESERVED_METADATA_KEYS);
        return sanitized;
    }

    private void publishAfterCommit(UUID mockId) {
        runAfterCommit(() -> wireMockService.publishMock(mockId));
    }

    private void publishUpdateAfterCommit(UUID mockId) {
        runAfterCommit(() -> wireMockService.updateMock(mockId));
    }

    private static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            // Outside of a transaction — execute inline so callers like
            // republish() still work.
            action.run();
        }
    }
}
