package com.wire.mate.service.controller;

import com.wire.mate.service.dto.mock.MockResponse;
import com.wire.mate.service.dto.project.CloneProjectRequest;
import com.wire.mate.service.dto.project.CreateProjectRequest;
import com.wire.mate.service.dto.project.ProjectResponse;
import com.wire.mate.service.dto.project.ProjectWithMocksResponse;
import com.wire.mate.service.service.MockService;
import com.wire.mate.service.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final MockService mockService;

    public ProjectController(ProjectService projectService, MockService mockService) {
        this.projectService = projectService;
        this.mockService = mockService;
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<ProjectResponse> getAllProjects() {
        return projectService.getAllProjects();
    }

    @GetMapping("/{projectId}")
    @ResponseStatus(HttpStatus.OK)
    public ProjectWithMocksResponse getProjectById(@PathVariable UUID projectId) {
        return projectService.getProjectById(projectId);
    }

    /**
     * List the mocks belonging to a project. Lives under the project URI so
     * that it composes naturally with the rest of the project resource.
     */
    @GetMapping("/{projectId}/mocks")
    @ResponseStatus(HttpStatus.OK)
    public List<MockResponse> getMocksByProjectId(@PathVariable UUID projectId) {
        return mockService.getMocksByProjectId(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse createProject(@Valid @RequestBody CreateProjectRequest request) {
        return projectService.createProject(request);
    }

    @PostMapping("/{projectId}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectWithMocksResponse cloneProject(@PathVariable UUID projectId,
                                                 @Valid @RequestBody CloneProjectRequest request) {
        return projectService.cloneProject(projectId, request);
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@PathVariable UUID projectId) {
        projectService.deleteProject(projectId);
    }
}
