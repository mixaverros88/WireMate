package com.wire.mate.service.service;

import com.wire.mate.service.dto.project.CloneProjectRequest;
import com.wire.mate.service.dto.project.CreateProjectRequest;
import com.wire.mate.service.dto.project.ProjectResponse;
import com.wire.mate.service.dto.project.ProjectWithMocksResponse;
import com.wire.mate.service.dto.project.UpdateProjectRequest;
import com.wire.mate.service.entity.Mock;
import com.wire.mate.service.entity.Project;
import com.wire.mate.service.exception.BadRequestExc;
import com.wire.mate.service.exception.NotFoundExc;
import com.wire.mate.service.logic.WireMockService;
import com.wire.mate.service.repository.MockRepository;
import com.wire.mate.service.repository.ProjectRepository;
import com.wire.mate.service.util.Util;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final MockRepository mockRepository;
    private final WireMockService wireMockService;

    public ProjectService(
            ProjectRepository projectRepository,
            MockRepository mockRepository,
            WireMockService wireMockService
    ) {
        this.projectRepository = projectRepository;
        this.mockRepository = mockRepository;
        this.wireMockService = wireMockService;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> getAll() {
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        return projectRepository
                .findAll(sort)
                .stream()
                .map(Project::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectWithMocksResponse getById(UUID projectId) {
        Project project = projectRepository
                .findById(projectId)
                .orElseThrow(() -> new NotFoundExc("Project Id " + projectId));

        List<Mock> mocks = mockRepository.findByProjectIdOrderByCreatedAtDesc(projectId);

        return new ProjectWithMocksResponse(
                project.getId(),
                project.getName(),
                mocks.stream().map(Mock::toDto).toList(),
                project.getCreatedAt()
        );
    }

    @Transactional
    public ProjectResponse create(CreateProjectRequest request) {
        if (projectRepository.existsByName(request.name())) {
            throw new BadRequestExc("Project with name: " + request.name() + " already exists");
        }
        return projectRepository.save(request.toEntity()).toDto();
    }

    @Transactional
    public ProjectResponse update(UUID projectId, UpdateProjectRequest request) {
        Project project = projectRepository
                .findById(projectId)
                .orElseThrow(() -> new NotFoundExc("Project ID: " + projectId));

        if (projectRepository.existsByName(request.name())) {
            throw new BadRequestExc("Project with name: " + request.name() + "  already exists");
        }

        project.setName(request.name());
        Project saved = projectRepository.save(project);

        mockRepository
                .findByProjectId(projectId)
                .forEach(it-> {
                    Map<String, Object> requestDefinition = it.getRequestDefinition();
                    requestDefinition.put("urlPath", Util.replaceFirstPath(requestDefinition.get("urlPath").toString(), it.getProject().getName()));
                    it.setRequestDefinition(requestDefinition);
                    wireMockService.updateStub(it.toWiremockRequest());
                });

        return saved.toDto();
    }

    @Transactional
    public void clone(UUID projectId, CloneProjectRequest request) {

        if (!projectRepository.existsById(projectId)) {
            throw new BadRequestExc("Project with id:" + projectId + " does not exist");
        }

        if (projectRepository.existsByName(request.name())) {
            throw new BadRequestExc("Project with name: " + request.name() + " already exists");
        }

        Project clonedProject = projectRepository.save(new Project(request.name()));

        List<Mock> toClone = mockRepository.findByProjectId(projectId)
                .stream()
                .map(it -> new Mock(
                        it.getName(),
                        it.getDescription(),
                        clonedProject,
                        it.getRequestDefinition(),
                        it.getResponseDefinition(),
                        it.isPersistent(),
                        it.getPriority(),
                        it.getMetadata(),
                        it.getServeEventListeners(),
                        it.getCurl())
                ).toList();

        mockRepository.saveAll(toClone);
    }

    @Transactional
    public void delete(UUID projectId) {
        Project project = projectRepository.findById(projectId).orElseThrow(() -> new NotFoundExc("Project ID: " +  projectId));
        wireMockService.deleteByProjectName(project.getId());
        projectRepository.delete(project);
    }



}
