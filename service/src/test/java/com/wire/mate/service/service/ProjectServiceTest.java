package com.wire.mate.service.service;

import com.wire.mate.service.dto.project.CloneProjectRequest;
import com.wire.mate.service.dto.project.CreateProjectRequest;
import com.wire.mate.service.dto.project.ProjectResponse;
import com.wire.mate.service.entity.Mock;
import com.wire.mate.service.entity.Project;
import com.wire.mate.service.exception.ProjectAlreadyExistsException;
import com.wire.mate.service.exception.ProjectNotFoundException;
import com.wire.mate.service.logic.WireMockService;
import com.wire.mate.service.repository.MockRepository;
import com.wire.mate.service.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @org.mockito.Mock private ProjectRepository projectRepository;
    @org.mockito.Mock private MockRepository mockRepository;
    @org.mockito.Mock private WireMockService wireMockService;

    @InjectMocks private ProjectService projectService;

    private Project sample;
    private UUID sampleId;

    @BeforeEach
    void setUp() {
        sampleId = UUID.randomUUID();
        sample = new Project("p1");
        sample.setId(sampleId);
    }

    @Test
    void createProject_rejects_duplicate_name() {
        when(projectRepository.existsByName("dup")).thenReturn(true);

        assertThatThrownBy(() -> projectService.createProject(new CreateProjectRequest("dup")))
                .isInstanceOf(ProjectAlreadyExistsException.class);

        verify(projectRepository, never()).save(any());
    }

    @Test
    void createProject_persists_and_returns_dto_for_new_name() {
        when(projectRepository.existsByName("new")).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        ProjectResponse response = projectService.createProject(new CreateProjectRequest("new"));

        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo("new");
        assertThat(response.id()).isNotNull();
    }

    @Test
    void getAllProjects_returns_list_sorted_by_created_at_desc() {
        when(projectRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")))
                .thenReturn(List.of(sample));

        List<ProjectResponse> projects = projectService.getAllProjects();

        assertThat(projects).hasSize(1);
        assertThat(projects.get(0).id()).isEqualTo(sampleId);
    }

    @Test
    void getProjectById_throws_when_missing() {
        UUID id = UUID.randomUUID();
        when(projectRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.getProjectById(id))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void cloneProject_does_not_publish_to_wiremock_inside_transaction() {
        UUID newId = UUID.randomUUID();
        when(projectRepository.findById(sampleId)).thenReturn(Optional.of(sample));
        when(projectRepository.existsByName("clone")).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(newId);
            return p;
        });
        Mock src = new Mock("m1", null, sample, Map.of(), Map.of(), false, 5, null, null, null);
        src.setId(UUID.randomUUID());
        when(mockRepository.findByProjectIdOrderByCreatedAtDesc(sampleId)).thenReturn(List.of(src));
        when(mockRepository.saveAll(any())).thenAnswer(inv -> {
            List<Mock> mocks = inv.getArgument(0);
            mocks.forEach(m -> m.setId(UUID.randomUUID()));
            return mocks;
        });

        projectService.cloneProject(sampleId, new CloneProjectRequest("clone"));

        // The WireMock publish is queued via a TransactionSynchronization that
        // only fires after commit. With no real tx in the test, it should
        // never have run synchronously.
        verify(wireMockService, never()).publishMock(any());
    }
}
