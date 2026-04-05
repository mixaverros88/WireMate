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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
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
class MockServiceTest {

    @Mock private MockRepository mockRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WireMockService wireMockService;

    @InjectMocks private MockService mockService;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = new Project("default");
        project.setId(projectId);
    }

    @Test
    void createMock_strips_reserved_metadata_keys() {
        Map<String, Object> tampered = new HashMap<>();
        tampered.put("safe", "ok");
        tampered.put("generatedBy", "attacker");
        tampered.put("projectName", "wrong");
        tampered.put("projectId", "wrong");

        CreateMockRequest request = new CreateMockRequest(
                "stub", null, projectId,
                Map.of("method", "GET"), Map.of("status", 200),
                false, 5, tampered, null, null);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(mockRepository.existsByNameAndProjectId("stub", projectId)).thenReturn(false);
        when(mockRepository.save(any(com.wire.mate.service.entity.Mock.class)))
                .thenAnswer(inv -> {
                    com.wire.mate.service.entity.Mock m = inv.getArgument(0);
                    m.setId(UUID.randomUUID());
                    return m;
                });

        MockResponse response = mockService.createMock(request);

        ArgumentCaptor<com.wire.mate.service.entity.Mock> captor =
                ArgumentCaptor.forClass(com.wire.mate.service.entity.Mock.class);
        verify(mockRepository).save(captor.capture());

        Map<String, Object> persisted = captor.getValue().getMetadata();
        assertThat(persisted).containsEntry("safe", "ok");
        assertThat(persisted).doesNotContainKeys("generatedBy", "projectName", "projectId");
        assertThat(response).isNotNull();
    }

    @Test
    void createMock_throws_when_project_missing() {
        UUID missing = UUID.randomUUID();
        when(projectRepository.findById(missing)).thenReturn(Optional.empty());

        CreateMockRequest req = new CreateMockRequest(
                "x", null, missing, Map.of(), Map.of(), false, 5, null, null, null);

        assertThatThrownBy(() -> mockService.createMock(req))
                .isInstanceOf(ProjectNotFoundException.class);
        verify(mockRepository, never()).save(any());
    }

    @Test
    void createMock_throws_when_duplicate_name_in_project() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(mockRepository.existsByNameAndProjectId("dup", projectId)).thenReturn(true);

        CreateMockRequest req = new CreateMockRequest(
                "dup", null, projectId, Map.of(), Map.of(), false, 5, null, null, null);

        assertThatThrownBy(() -> mockService.createMock(req))
                .isInstanceOf(MockAlreadyExistsException.class);
    }

    @Test
    void updateMock_throws_when_id_missing() {
        UUID id = UUID.randomUUID();
        when(mockRepository.findById(id)).thenReturn(Optional.empty());

        UpdateMockRequest req = new UpdateMockRequest(
                "x", null, Map.of(), Map.of(), false, 5, null, null, null);

        assertThatThrownBy(() -> mockService.updateMock(id, req))
                .isInstanceOf(MockNotFoundException.class);
    }

    @Test
    void cloneMock_throws_when_target_name_already_exists() {
        UUID id = UUID.randomUUID();
        com.wire.mate.service.entity.Mock src = new com.wire.mate.service.entity.Mock(
                "src", null, project, Map.of(), Map.of(), false, 5, null, null, null);
        src.setId(id);

        when(mockRepository.findById(id)).thenReturn(Optional.of(src));
        when(mockRepository.existsByNameAndProjectId("clone", projectId)).thenReturn(true);

        assertThatThrownBy(() -> mockService.cloneMock(id, new CloneMockRequest("clone")))
                .isInstanceOf(MockAlreadyExistsException.class);
    }

    @Test
    void moveMock_throws_when_target_project_missing() {
        UUID mockId = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        com.wire.mate.service.entity.Mock entity = new com.wire.mate.service.entity.Mock(
                "n", null, project, Map.of(), Map.of(), false, 5, null, null, null);
        entity.setId(mockId);

        when(mockRepository.findById(mockId)).thenReturn(Optional.of(entity));
        when(projectRepository.findById(otherProject)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mockService.moveMock(mockId, new MoveMockRequest(otherProject)))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void republish_throws_404_for_unknown_mock_instead_of_calling_wiremock() {
        UUID id = UUID.randomUUID();
        when(mockRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> mockService.republish(id))
                .isInstanceOf(MockNotFoundException.class);
        verify(wireMockService, never()).publishMock(any());
    }
}
