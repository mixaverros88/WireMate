package com.wire.mate.service.service;

import com.wire.mate.service.dto.project.CreateProjectRequest;
import com.wire.mate.service.dto.project.ProjectResponse;
import com.wire.mate.service.dto.project.ProjectWithMocksResponse;
import com.wire.mate.service.dto.project.UpdateProjectRequest;
import com.wire.mate.service.entity.Mock;
import com.wire.mate.service.entity.Project;
import com.wire.mate.service.exception.BadRequestExc;
import com.wire.mate.service.exception.NotFoundExc;
import com.wire.mate.service.logic.WireMockService;
import com.wire.mate.service.repository.ProjectRepository;
import com.wire.mate.service.util.Util;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final WireMockService wireMockService;

    public ProjectService(
            ProjectRepository projectRepository,
            WireMockService wireMockService
    ) {
        this.projectRepository = projectRepository;
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

        return new ProjectWithMocksResponse(
                project.getId(),
                project.getName(),
                project.getMockList().stream()
                        .sorted(Comparator.comparing(Mock::getCreatedAt).reversed())
                        .map(Mock::toDto)
                        .toList(),
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

        updateUrlPathOnEveryRequestDefinitionInMock(saved);

        return saved.toDto();
    }

    @Transactional
    public void delete(UUID projectId) {
        Project project = projectRepository.
                findById(projectId)
                .orElseThrow(() -> new NotFoundExc("Project ID: " + projectId));

        wireMockService.deleteByProjectName(project.getId());
        projectRepository.delete(project);
    }

    // WireMock matches requests with exactly one of these four mutually
    // exclusive keys, so the rename rewrite has to find whichever one is
    // present rather than hard-coding `urlPath` (a mock created with
    // `url`, `urlPattern`, or `urlPathPattern` would otherwise NPE on
    // `requestDefinition.get("urlPath").toString()`).
    private static final List<String> URL_MATCH_KEYS =
            List.of("url", "urlPath", "urlPattern", "urlPathPattern");

    private void updateUrlPathOnEveryRequestDefinitionInMock(Project project) {
        String friendlyUrlPath = Util.toUrlFriendly(project.getName());
        project
                .getMockList()
                .forEach(it -> {
                    Map<String, Object> requestDefinition = it.getRequestDefinition();
                    String urlKey = firstPresentUrlKey(requestDefinition);
                    if (urlKey != null) {
                        String finalUrlPath = Util.replaceFirstPath(
                                requestDefinition.get(urlKey).toString(),
                                friendlyUrlPath
                        );
                        requestDefinition.put(urlKey, finalUrlPath);
                        it.setRequestDefinition(requestDefinition);
                    }
                    // The cURL refresh and WireMock re-publish run even
                    // when no URL matcher was present (e.g. a "match any"
                    // stub) so the upstream stub and the stored cURL
                    // stay in step with the renamed project.
                    it.setCurl(Util.curlReplaceFirstPath(it.getCurl(), friendlyUrlPath));
                    wireMockService.updateStub(it.toWiremockRequest());
                });
    }

    private static String firstPresentUrlKey(Map<String, Object> requestDefinition) {
        if (requestDefinition == null) return null;
        for (String key : URL_MATCH_KEYS) {
            if (requestDefinition.get(key) != null) {
                return key;
            }
        }
        return null;
    }

}
