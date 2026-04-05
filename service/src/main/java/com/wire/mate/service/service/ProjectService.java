package com.wire.mate.service.service;

import com.wire.mate.service.dto.project.CloneProjectRequest;
import com.wire.mate.service.dto.project.CreateProjectRequest;
import com.wire.mate.service.dto.project.ProjectResponse;
import com.wire.mate.service.dto.project.ProjectWithMocksResponse;
import com.wire.mate.service.entity.Mock;
import com.wire.mate.service.entity.Project;
import com.wire.mate.service.exception.ProjectAlreadyExistsException;
import com.wire.mate.service.exception.ProjectNotFoundException;
import com.wire.mate.service.logic.WireMockService;
import com.wire.mate.service.repository.MockRepository;
import com.wire.mate.service.repository.ProjectRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final MockRepository mockRepository;
    private final WireMockService wireMockService;

    public ProjectService(ProjectRepository projectRepository,
                          MockRepository mockRepository,
                          WireMockService wireMockService) {
        this.projectRepository = projectRepository;
        this.mockRepository = mockRepository;
        this.wireMockService = wireMockService;
    }

    @Transactional(readOnly = true)
    public ProjectWithMocksResponse getProjectById(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        List<Mock> mocks = mockRepository.findByProjectIdOrderByCreatedAtDesc(projectId);

        return new ProjectWithMocksResponse(
                project.getId(),
                project.getName(),
                mocks.stream().map(Mock::toDto).toList(),
                project.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> getAllProjects() {
        return projectRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(Project::toDto)
                .toList();
    }

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request) {
        if (projectRepository.existsByName(request.name())) {
            throw new ProjectAlreadyExistsException(request.name());
        }
        return projectRepository.save(request.toEntity()).toDto();
    }

    /**
     * Clone a project including all of its mocks. Strategy:
     *
     * <ol>
     *   <li>Persist the new project + all cloned mocks in a single transaction
     *       using {@code saveAll} so Hibernate can batch the inserts.</li>
     *   <li>Publish each mock to WireMock <em>after</em> the database commit.
     *       If WireMock fails for one stub the rest are still consistent and
     *       the cross-check task will eventually retry the missing ones.</li>
     * </ol>
     *
     * <p>This avoids the previous "save-in-a-loop while publishing inside the
     * transaction" pattern, which both held the DB connection during HTTP and
     * defeated Hibernate's batch-insert capability.</p>
     */
    @Transactional
    public ProjectWithMocksResponse cloneProject(UUID projectId, CloneProjectRequest request) {
        Project sourceProject = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        if (projectRepository.existsByName(request.name())) {
            throw new ProjectAlreadyExistsException(request.name());
        }

        Project clonedProject = projectRepository.save(new Project(request.name()));

        List<Mock> sourceMocks = mockRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        List<Mock> toClone = new ArrayList<>(sourceMocks.size());
        for (Mock src : sourceMocks) {
            toClone.add(new Mock(
                    src.getName(),
                    src.getDescription(),
                    clonedProject,
                    src.getRequestDefinition(),
                    src.getResponseDefinition(),
                    src.isPersistent(),
                    src.getPriority(),
                    src.getMetadata(),
                    src.getServeEventListeners(),
                    src.getCurl()
            ));
        }
        List<Mock> savedMocks = mockRepository.saveAll(toClone);

        List<UUID> publishIds = savedMocks.stream().map(Mock::getId).toList();

        // Capture the response data inside the tx (no further DB calls afterwards).
        ProjectWithMocksResponse response = new ProjectWithMocksResponse(
                clonedProject.getId(),
                clonedProject.getName(),
                savedMocks.stream().map(Mock::toDto).toList(),
                clonedProject.getCreatedAt()
        );

        // Schedule WireMock publishes once the DB tx commits — keeps HTTP out
        // of the transactional path so we don't hold the connection for the
        // duration of N remote calls.
        org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                publishIds.forEach(wireMockService::publishMock);
                            }
                        });

        return response;
    }

    /**
     * Delete a project and all of its mocks. The {@code mocks.project_id} FK is
     * declared {@code ON DELETE CASCADE}, so we only need to delete the project
     * row and the database removes the children atomically.
     */
    @Transactional
    public void deleteProject(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        String projectName = project.getName();
        projectRepository.delete(project);

        // WireMock cleanup happens after the DB tx commits — if it fails the
        // cross-check task will surface the orphaned stubs as notifications.
        org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                wireMockService.deleteByProjectName(projectName);
                            }
                        });
    }
}
