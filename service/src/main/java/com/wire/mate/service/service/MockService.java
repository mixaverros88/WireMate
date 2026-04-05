package com.wire.mate.service.service;

import com.wire.mate.service.dto.mock.CloneMockRequest;
import com.wire.mate.service.dto.mock.CreateMockRequest;
import com.wire.mate.service.dto.mock.MockResponse;
import com.wire.mate.service.dto.mock.MoveMockRequest;
import com.wire.mate.service.dto.mock.UpdateMockRequest;
import com.wire.mate.service.entity.Mock;
import com.wire.mate.service.entity.Project;
import com.wire.mate.service.exception.BadRequestExc;
import com.wire.mate.service.exception.NotFoundExc;
import com.wire.mate.service.exception.WireMockNotFound;
import com.wire.mate.service.logic.WireMockService;
import com.wire.mate.service.repository.MockRepository;
import com.wire.mate.service.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class MockService {

    private final MockRepository mockRepository;
    private final ProjectRepository projectRepository;
    private final WireMockService wireMockService;

    public MockService(
            MockRepository mockRepository,
            ProjectRepository projectRepository,
            WireMockService wireMockService
    ) {
        this.mockRepository = mockRepository;
        this.projectRepository = projectRepository;
        this.wireMockService = wireMockService;
    }

    @Transactional
    public MockResponse create(CreateMockRequest request) {
        Project project = projectRepository
                .findById(request.projectId())
                .orElseThrow(() -> new NotFoundExc("Project id" + request.projectId()));

        if (mockRepository.existsByNameAndProjectId(request.name(), request.projectId())) {
            throw new BadRequestExc("Mock name is already exists");
        }

        Mock saved = mockRepository.save(request.toEntity(project));
        wireMockService.publishStub(saved.toWiremockRequest());
        return saved.toDto();
    }

    @Transactional
    public MockResponse update(UUID mockId, UpdateMockRequest request) {
        Mock mock = mockRepository
                .findById(mockId)
                .orElseThrow(() -> new NotFoundExc("Mock id" + mockId));

        if (!mock.getName().equals(request.name())
                && mockRepository.existsByNameAndProjectId(request.name(), mock.getProject().getId())) {
            throw new BadRequestExc("Mock name is already exists");
        }

        mock.setName(request.name());
        mock.setDescription(request.description());
        mock.setRequestDefinition(request.request());
        mock.setResponseDefinition(request.response());
        mock.setPersistent(request.persistent());
        mock.setPriority(request.priority());
        mock.setMetadata(request.metadata());
        mock.setServeEventListeners(request.serveEventListeners());
        mock.setCurl(request.curl());

        Mock saved = mockRepository.save(mock);
        wireMockService.updateStub(saved.toWiremockRequest());
        return saved.toDto();
    }

    @Transactional
    public MockResponse clone(UUID mockId, CloneMockRequest request) {
        Mock sourceMock = mockRepository
                .findById(mockId)
                .orElseThrow(() -> new NotFoundExc("Mock  id" + mockId));

        Project project = sourceMock.getProject();

        if (mockRepository.existsByNameAndProjectId(request.name(), project.getId())) {
            throw new BadRequestExc("Mock name is already exists");
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

        wireMockService.publishStub(saved.toWiremockRequest());
        return saved.toDto();
    }

    @Transactional
    public MockResponse move(UUID mockId, MoveMockRequest request) {
        Mock mock = mockRepository
                .findById(mockId)
                .orElseThrow(() -> new NotFoundExc("Mock  id" + mockId));

        Project targetProject = projectRepository
                .findById(request.targetProjectId())
                .orElseThrow(() -> new NotFoundExc("Mock Id: " + request.targetProjectId()));

        if (mockRepository.existsByNameAndProjectId(mock.getName(), targetProject.getId())) {
            mock.setName(mock.getName() + " " + UUID.randomUUID() + " (the uuid added to by pass the name collision)");
        }

        mock.setProject(targetProject);
        Mock saved = mockRepository.save(mock);
        wireMockService.publishStub(saved.toWiremockRequest());
        return saved.toDto();
    }

    @Transactional(readOnly = true)
    public MockResponse getById(UUID mockId) {
        return mockRepository
                .findById(mockId)
                .orElseThrow(() -> new NotFoundExc("Mock  id" + mockId))
                .toDto();
    }

    @Transactional(readOnly = true)
    public boolean existsByNameInProject(UUID projectId, String name) {
        if (!projectRepository.existsById(projectId)) {
            throw new NotFoundExc("Project id" + projectId);
        }
        return mockRepository.existsByNameAndProjectId(name, projectId);
    }

    @Transactional(readOnly = true)
    public boolean existsByUrlInProject(UUID projectId, String url) {
        if (!projectRepository.existsById(projectId)) {
            throw new NotFoundExc("Project id" + projectId);
        }
        return mockRepository.existsByUrlAndProjectId(url, projectId);
    }

    @Transactional
    public void delete(UUID mockId) {
        if (!mockRepository.existsById(mockId)) {
            throw new NotFoundExc("Mock  id" + mockId);
        }

        try {
            wireMockService.deleteStub(mockId);
        }catch (WireMockNotFound wireMockNotFound){
           //TODO: just log
        }

        mockRepository.deleteById(mockId);
    }

    @Transactional(readOnly = true)
    public void republish(UUID mockId) {
        Mock mock = mockRepository.findById(mockId).orElseThrow(() -> new NotFoundExc("Mock  id" + mockId));

        try {
            wireMockService.getById(mock.toWiremockRequest().id());
        } catch (WireMockNotFound e) {
            throw new BadRequestExc("");
        }

        wireMockService.publishStub(mock.toWiremockRequest());
    }

}
