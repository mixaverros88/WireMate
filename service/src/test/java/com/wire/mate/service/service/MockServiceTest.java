package com.wire.mate.service.service;

import com.wire.mate.service.dto.mock.CloneMockRequest;
import com.wire.mate.service.dto.mock.CreateMockRequest;
import com.wire.mate.service.dto.mock.MoveMockRequest;
import com.wire.mate.service.dto.mock.UpdateMockRequest;
import com.wire.mate.service.entity.Project;
import com.wire.mate.service.exception.BadRequestExc;
import com.wire.mate.service.exception.NotFoundExc;
import com.wire.mate.service.exception.WireMockExc;
import com.wire.mate.service.exception.WireMockNotFoundExe;
import com.wire.mate.service.gateway.dto.WiremockReq;
import com.wire.mate.service.logic.WireMockService;
import com.wire.mate.service.repository.MockRepository;
import com.wire.mate.service.repository.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MockServiceTest {

    @Mock private MockRepository mockRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WireMockService wireMockService;

    @InjectMocks private MockService service;

    private static Project project(String name) {
        var p = new Project(name);
        p.setId(UUID.randomUUID());
        return p;
    }

    private static com.wire.mate.service.entity.Mock mock(Project p, String name) {
        var m = new com.wire.mate.service.entity.Mock(name, "desc", p,
                new HashMap<>(Map.of("urlPath", "/" + name)),
                new HashMap<>(Map.of("status", 200)),
                false, 5,
                new HashMap<>(Map.of("projectId", p.getId().toString())),
                List.of(),
                "curl http://h/x");
        m.setId(UUID.randomUUID());
        return m;
    }

    private static CreateMockRequest createReq(UUID projectId, String name) {
        return new CreateMockRequest(
                name, "desc", projectId,
                new HashMap<>(Map.of("urlPath", "/" + name)),
                new HashMap<>(Map.of("status", 200)),
                false, 5, new HashMap<>(), List.of(), "curl");
    }

    private static UpdateMockRequest updateReq(String name) {
        return new UpdateMockRequest(name, "new-desc",
                new HashMap<>(Map.of("urlPath", "/new")),
                new HashMap<>(Map.of("status", 201)),
                true, 9, new HashMap<>(Map.of("k", "v")), List.of(), "new-curl");
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("throws NotFoundExc when project missing")
        void projectMissing() {
            var req = createReq(UUID.randomUUID(), "m");
            when(projectRepository.findById(req.projectId())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(req))
                    .isInstanceOf(NotFoundExc.class);

            verifyNoInteractions(wireMockService);
            verify(mockRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws BadRequestExc on duplicate name within project")
        void duplicateName() {
            var p = project("p");
            var req = createReq(p.getId(), "dup");
            when(projectRepository.findById(p.getId())).thenReturn(Optional.of(p));
            when(mockRepository.existsByNameAndProjectId("dup", p.getId())).thenReturn(true);

            assertThatThrownBy(() -> service.create(req))
                    .isInstanceOf(BadRequestExc.class)
                    .hasMessageContaining("already exists");

            verify(mockRepository, never()).save(any());
            verifyNoInteractions(wireMockService);
        }

        @Test
        @DisplayName("saves entity then publishes to WireMock with id from saved entity")
        void happyPath() {
            var p = project("p");
            var req = createReq(p.getId(), "m");
            when(projectRepository.findById(p.getId())).thenReturn(Optional.of(p));
            when(mockRepository.existsByNameAndProjectId("m", p.getId())).thenReturn(false);
            when(mockRepository.save(any(com.wire.mate.service.entity.Mock.class))).thenAnswer(inv -> {
                var arg = inv.<com.wire.mate.service.entity.Mock>getArgument(0);
                arg.setId(UUID.randomUUID());
                return arg;
            });

            var resp = service.create(req);

            var captor = ArgumentCaptor.forClass(WiremockReq.class);
            verify(wireMockService).publishStub(captor.capture());
            assertThat(captor.getValue().id()).isEqualTo(resp.id());
            assertThat(captor.getValue().name()).isEqualTo("m");
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("throws NotFoundExc when mock missing")
        void mockMissing() {
            var id = UUID.randomUUID();
            when(mockRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(id, updateReq("x")))
                    .isInstanceOf(NotFoundExc.class);
        }

        @Test
        @DisplayName("skips duplicate-name check when name is unchanged")
        void sameNameSkipsCheck() {
            var p = project("p");
            var m = mock(p, "keep");
            when(mockRepository.findById(m.getId())).thenReturn(Optional.of(m));
            when(mockRepository.save(m)).thenReturn(m);

            service.update(m.getId(), updateReq("keep"));

            verify(mockRepository, never()).existsByNameAndProjectId(any(), any());
            verify(wireMockService).updateStub(any());
        }

        @Test
        @DisplayName("throws BadRequestExc when renamed to existing name in same project")
        void renamedDuplicate() {
            var p = project("p");
            var m = mock(p, "old");
            when(mockRepository.findById(m.getId())).thenReturn(Optional.of(m));
            when(mockRepository.existsByNameAndProjectId("new-name", p.getId())).thenReturn(true);

            assertThatThrownBy(() -> service.update(m.getId(), updateReq("new-name")))
                    .isInstanceOf(BadRequestExc.class);

            verify(mockRepository, never()).save(any());
            verifyNoInteractions(wireMockService);
        }

        @Test
        @DisplayName("update never publishes via WireMock when only the mock id is invalid (no save)")
        // Sanity: update() should never call wireMockService if mock lookup fails.
        void updateMissingMockSkipsWireMock() {
            var id = UUID.randomUUID();
            when(mockRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(id, updateReq("x")))
                    .isInstanceOf(NotFoundExc.class);

            verifyNoInteractions(wireMockService);
            verify(mockRepository, never()).save(any());
            verify(mockRepository, never()).existsByNameAndProjectId(any(), any());
        }

        @Test
        @DisplayName("PINNED: caller metadata overwrites — Util.buildMetadata's projectId is lost if absent in request")
        // TODO bug: update should merge or preserve projectId; replacing wholesale breaks
        // WireMock cleanup-by-metadata later.
        void updateOverwritesProjectIdMetadata() {
            var p = project("p");
            var m = mock(p, "n");
            when(mockRepository.findById(m.getId())).thenReturn(Optional.of(m));
            when(mockRepository.save(m)).thenReturn(m);

            // Caller-supplied metadata has no projectId entry
            var req = new UpdateMockRequest("n", "d",
                    new HashMap<>(Map.of("urlPath", "/x")),
                    new HashMap<>(Map.of("status", 200)),
                    false, 5,
                    new HashMap<>(Map.of("note", "no-project-id-here")),
                    List.of(), "curl");

            service.update(m.getId(), req);

            assertThat(m.getMetadata())
                    .containsEntry("note", "no-project-id-here")
                    .doesNotContainKey("projectId");
        }

        @Test
        @DisplayName("applies all setters and publishes update")
        void appliesAllFields() {
            var p = project("p");
            var m = mock(p, "old");
            when(mockRepository.findById(m.getId())).thenReturn(Optional.of(m));
            when(mockRepository.existsByNameAndProjectId("renamed", p.getId())).thenReturn(false);
            when(mockRepository.save(m)).thenReturn(m);

            var req = updateReq("renamed");
            service.update(m.getId(), req);

            assertThat(m.getName()).isEqualTo("renamed");
            assertThat(m.getDescription()).isEqualTo("new-desc");
            assertThat(m.getRequestDefinition()).containsEntry("urlPath", "/new");
            assertThat(m.getResponseDefinition()).containsEntry("status", 201);
            assertThat(m.isPersistent()).isTrue();
            assertThat(m.getPriority()).isEqualTo(9);
            assertThat(m.getMetadata()).containsEntry("k", "v");
            assertThat(m.getCurl()).isEqualTo("new-curl");
            verify(wireMockService).updateStub(any(WiremockReq.class));
        }
    }

    @Nested
    @DisplayName("create — metadata handling")
    class CreateMetadata {

        @Test
        @DisplayName("PINNED: caller-supplied metadata is dropped; Util.buildMetadata is used")
        // TODO bug: CreateMockRequest.metadata is unused; consider deep-merging instead.
        void callerMetadataDropped() {
            var p = project("p");
            var callerMeta = new HashMap<String, Object>(Map.of("caller", "wants-this"));
            var req = new CreateMockRequest(
                    "m", "d", p.getId(),
                    new HashMap<>(Map.of("urlPath", "/x")),
                    new HashMap<>(Map.of("status", 200)),
                    false, 5, callerMeta, List.of(), "curl");
            when(projectRepository.findById(p.getId())).thenReturn(Optional.of(p));
            when(mockRepository.existsByNameAndProjectId("m", p.getId())).thenReturn(false);
            when(mockRepository.save(any(com.wire.mate.service.entity.Mock.class))).thenAnswer(inv -> {
                var arg = inv.<com.wire.mate.service.entity.Mock>getArgument(0);
                arg.setId(UUID.randomUUID());
                return arg;
            });

            var resp = service.create(req);

            // Metadata contains generatedBy + projectId (from Util.buildMetadata), NOT "caller"
            assertThat(resp.metadata())
                    .containsEntry("generatedBy", "wiremate")
                    .containsEntry("projectId", p.getId())
                    .doesNotContainKey("caller");
        }
    }

    @Nested
    @DisplayName("clone")
    class Clone {

        @Test
        @DisplayName("throws NotFoundExc when source mock missing")
        void sourceMissing() {
            var id = UUID.randomUUID();
            when(mockRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.clone(id, new CloneMockRequest("copy")))
                    .isInstanceOf(NotFoundExc.class);
        }

        @Test
        @DisplayName("throws BadRequestExc when name already exists in project")
        void duplicate() {
            var p = project("p");
            var src = mock(p, "orig");
            when(mockRepository.findById(src.getId())).thenReturn(Optional.of(src));
            when(mockRepository.existsByNameAndProjectId("dup", p.getId())).thenReturn(true);

            assertThatThrownBy(() -> service.clone(src.getId(), new CloneMockRequest("dup")))
                    .isInstanceOf(BadRequestExc.class);
        }

        @Test
        @DisplayName("PINNED: clone shares JSONB map references with source (no deep copy)")
        // TODO bug: clone uses the all-args ctor; the dedicated Mock(Mock other, Project)
        // ctor does HashMap-copy semantics but isn't used here. Mutating the source after
        // clone will also mutate the clone.
        void cloneSharesReferences() {
            var p = project("p");
            var src = mock(p, "orig");
            when(mockRepository.findById(src.getId())).thenReturn(Optional.of(src));
            when(mockRepository.existsByNameAndProjectId("copy", p.getId())).thenReturn(false);
            // Capture the saved (cloned) entity
            var savedRef = new java.util.concurrent.atomic.AtomicReference<com.wire.mate.service.entity.Mock>();
            when(mockRepository.save(any(com.wire.mate.service.entity.Mock.class))).thenAnswer(inv -> {
                var arg = inv.<com.wire.mate.service.entity.Mock>getArgument(0);
                arg.setId(UUID.randomUUID());
                savedRef.set(arg);
                return arg;
            });

            service.clone(src.getId(), new CloneMockRequest("copy"));

            // Same reference: mutating source mutates clone too
            assertThat(savedRef.get().getRequestDefinition())
                    .isSameAs(src.getRequestDefinition());
            assertThat(savedRef.get().getResponseDefinition())
                    .isSameAs(src.getResponseDefinition());
            assertThat(savedRef.get().getMetadata())
                    .isSameAs(src.getMetadata());
        }

        @Test
        @DisplayName("creates new mock with cloned fields and publishes to WireMock")
        void happyPath() {
            var p = project("p");
            var src = mock(p, "orig");
            when(mockRepository.findById(src.getId())).thenReturn(Optional.of(src));
            when(mockRepository.existsByNameAndProjectId("copy", p.getId())).thenReturn(false);
            when(mockRepository.save(any(com.wire.mate.service.entity.Mock.class))).thenAnswer(inv -> {
                var arg = inv.<com.wire.mate.service.entity.Mock>getArgument(0);
                arg.setId(UUID.randomUUID());
                return arg;
            });

            var resp = service.clone(src.getId(), new CloneMockRequest("copy"));

            assertThat(resp.name()).isEqualTo("copy");
            assertThat(resp.projectId()).isEqualTo(p.getId());
            verify(wireMockService).publishStub(any(WiremockReq.class));
        }
    }

    @Nested
    @DisplayName("move")
    class Move {

        @Test
        @DisplayName("throws NotFoundExc when mock missing")
        void mockMissing() {
            var id = UUID.randomUUID();
            when(mockRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.move(id, new MoveMockRequest(UUID.randomUUID())))
                    .isInstanceOf(NotFoundExc.class);
        }

        @Test
        @DisplayName("throws NotFoundExc when target project missing")
        void targetMissing() {
            var p = project("p");
            var m = mock(p, "n");
            var targetId = UUID.randomUUID();
            when(mockRepository.findById(m.getId())).thenReturn(Optional.of(m));
            when(projectRepository.findById(targetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.move(m.getId(), new MoveMockRequest(targetId)))
                    .isInstanceOf(NotFoundExc.class);
        }

        @Test
        @DisplayName("on name collision in target, suffixes mock name with a UUID marker")
        void collisionSuffix() {
            var src = project("src");
            var dst = project("dst");
            var m = mock(src, "shared");
            when(mockRepository.findById(m.getId())).thenReturn(Optional.of(m));
            when(projectRepository.findById(dst.getId())).thenReturn(Optional.of(dst));
            when(mockRepository.existsByNameAndProjectId("shared", dst.getId())).thenReturn(true);
            when(mockRepository.save(m)).thenReturn(m);

            service.move(m.getId(), new MoveMockRequest(dst.getId()));

            assertThat(m.getName())
                    .startsWith("shared ")
                    .contains("(the uuid added to by pass the name collision)");
            assertThat(m.getProject()).isEqualTo(dst);
        }

        @Test
        @DisplayName("PINNED: move to SAME project corrupts the name with a UUID-suffix marker")
        // TODO bug: collision check fires when target == source because the mock's own
        // name exists in that project. Move-to-same-project should be a no-op (or rejected).
        void moveToSameProjectCorruptsName() {
            var p = project("p");
            var m = mock(p, "name");
            when(mockRepository.findById(m.getId())).thenReturn(Optional.of(m));
            when(projectRepository.findById(p.getId())).thenReturn(Optional.of(p));
            when(mockRepository.existsByNameAndProjectId("name", p.getId())).thenReturn(true);
            when(mockRepository.save(m)).thenReturn(m);

            service.move(m.getId(), new MoveMockRequest(p.getId()));

            assertThat(m.getName())
                    .startsWith("name ")
                    .contains("(the uuid added to by pass the name collision)");
        }

        @Test
        @DisplayName("PINNED: move does NOT update metadata.projectId — stale after cross-project move")
        // TODO bug: metadata.projectId still references the source project; WireMock cleanup
        // via deleteByProjectName(targetId) will not match this stub.
        void moveDoesNotUpdateMetadata() {
            var src = project("src");
            var dst = project("dst");
            var m = mock(src, "n"); // helper builds metadata with projectId=src.id
            when(mockRepository.findById(m.getId())).thenReturn(Optional.of(m));
            when(projectRepository.findById(dst.getId())).thenReturn(Optional.of(dst));
            when(mockRepository.existsByNameAndProjectId("n", dst.getId())).thenReturn(false);
            when(mockRepository.save(m)).thenReturn(m);

            service.move(m.getId(), new MoveMockRequest(dst.getId()));

            // Project reference moved but metadata still points at the source project
            assertThat(m.getProject()).isEqualTo(dst);
            assertThat(m.getMetadata()).containsEntry("projectId", src.getId().toString());
        }

        @Test
        @DisplayName("PINNED: move calls publishStub, not updateStub (relies on WireMock idempotency)")
        // Design observation: move() always POSTs a fresh mapping rather than PUT-updating.
        // WireMock's POST with an existing id typically replaces, but the behavior depends
        // on WireMock's response semantics. Pin this in case future refactors want updateStub.
        void moveUsesPublishStub() {
            var src = project("src");
            var dst = project("dst");
            var m = mock(src, "n");
            when(mockRepository.findById(m.getId())).thenReturn(Optional.of(m));
            when(projectRepository.findById(dst.getId())).thenReturn(Optional.of(dst));
            when(mockRepository.existsByNameAndProjectId("n", dst.getId())).thenReturn(false);
            when(mockRepository.save(m)).thenReturn(m);

            service.move(m.getId(), new MoveMockRequest(dst.getId()));

            verify(wireMockService).publishStub(any(WiremockReq.class));
            verify(wireMockService, never()).updateStub(any());
        }

        @Test
        @DisplayName("no suffix when no collision; project reference is updated")
        void cleanMove() {
            var src = project("src");
            var dst = project("dst");
            var m = mock(src, "unique");
            when(mockRepository.findById(m.getId())).thenReturn(Optional.of(m));
            when(projectRepository.findById(dst.getId())).thenReturn(Optional.of(dst));
            when(mockRepository.existsByNameAndProjectId("unique", dst.getId())).thenReturn(false);
            when(mockRepository.save(m)).thenReturn(m);

            service.move(m.getId(), new MoveMockRequest(dst.getId()));

            assertThat(m.getName()).isEqualTo("unique");
            assertThat(m.getProject()).isEqualTo(dst);
            verify(wireMockService).publishStub(any(WiremockReq.class));
        }
    }

    @Nested
    @DisplayName("getById")
    class GetById {

        @Test
        @DisplayName("throws NotFoundExc on miss")
        void miss() {
            var id = UUID.randomUUID();
            when(mockRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(id))
                    .isInstanceOf(NotFoundExc.class);
        }

        @Test
        @DisplayName("returns DTO on hit")
        void hit() {
            var p = project("p");
            var m = mock(p, "n");
            when(mockRepository.findById(m.getId())).thenReturn(Optional.of(m));

            assertThat(service.getById(m.getId()).name()).isEqualTo("n");
        }
    }

    @Nested
    @DisplayName("existsByNameInProject / existsByUrlInProject")
    class Exists {

        @Test
        @DisplayName("throws NotFoundExc when project missing (byName)")
        void byName_projectMissing() {
            var pid = UUID.randomUUID();
            when(projectRepository.existsById(pid)).thenReturn(false);

            assertThatThrownBy(() -> service.existsByNameInProject(pid, "x"))
                    .isInstanceOf(NotFoundExc.class);
        }

        @Test
        @DisplayName("delegates to repository (byName)")
        void byName_delegates() {
            var pid = UUID.randomUUID();
            when(projectRepository.existsById(pid)).thenReturn(true);
            when(mockRepository.existsByNameAndProjectId("x", pid)).thenReturn(true);

            assertThat(service.existsByNameInProject(pid, "x")).isTrue();
        }

        @Test
        @DisplayName("throws NotFoundExc when project missing (byUrl)")
        void byUrl_projectMissing() {
            var pid = UUID.randomUUID();
            when(projectRepository.existsById(pid)).thenReturn(false);

            assertThatThrownBy(() -> service.existsByUrlInProject(pid, "/u"))
                    .isInstanceOf(NotFoundExc.class);
        }

        @Test
        @DisplayName("delegates to repository (byUrl)")
        void byUrl_delegates() {
            var pid = UUID.randomUUID();
            when(projectRepository.existsById(pid)).thenReturn(true);
            when(mockRepository.existsByUrlAndProjectId("/u", pid)).thenReturn(false);

            assertThat(service.existsByUrlInProject(pid, "/u")).isFalse();
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("throws NotFoundExc when mock missing")
        void mockMissing() {
            var id = UUID.randomUUID();
            when(mockRepository.existsById(id)).thenReturn(false);

            assertThatThrownBy(() -> service.delete(id))
                    .isInstanceOf(NotFoundExc.class);

            verifyNoInteractions(wireMockService);
            verify(mockRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("happy path deletes from WireMock then DB")
        void happyPath() throws Exception {
            var id = UUID.randomUUID();
            when(mockRepository.existsById(id)).thenReturn(true);

            service.delete(id);

            verify(wireMockService).deleteStub(id);
            verify(mockRepository).deleteById(id);
        }

        @Test
        @DisplayName("swallows WireMockNotFoundExe and still deletes from DB")
        void swallowNotFound() throws Exception {
            var id = UUID.randomUUID();
            when(mockRepository.existsById(id)).thenReturn(true);
            doThrow(new WireMockNotFoundExe("missing"))
                    .when(wireMockService).deleteStub(id);

            service.delete(id); // does not throw

            verify(mockRepository).deleteById(id);
        }

        @Test
        @DisplayName("does NOT swallow WireMockExc — DB delete is skipped")
        void doesNotSwallowGeneric() throws Exception {
            var id = UUID.randomUUID();
            when(mockRepository.existsById(id)).thenReturn(true);
            doThrow(new WireMockExc("boom", new RuntimeException()))
                    .when(wireMockService).deleteStub(id);

            assertThatThrownBy(() -> service.delete(id))
                    .isInstanceOf(WireMockExc.class);

            verify(mockRepository, never()).deleteById(any());
        }
    }

    @Nested
    @DisplayName("republish")
    class Republish {

        @Test
        @DisplayName("throws NotFoundExc when mock missing in DB")
        void notInDb() {
            var id = UUID.randomUUID();
            when(mockRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.republish(id))
                    .isInstanceOf(NotFoundExc.class);
        }

        @Test
        @DisplayName("throws BadRequestExc when mock missing in WireMock")
        void notInWireMock() throws Exception {
            var p = project("p");
            var m = mock(p, "n");
            when(mockRepository.findById(m.getId())).thenReturn(Optional.of(m));
            doThrow(new WireMockNotFoundExe("missing"))
                    .when(wireMockService).getById(m.getId());

            assertThatThrownBy(() -> service.republish(m.getId()))
                    .isInstanceOf(BadRequestExc.class)
                    .hasMessageContaining(m.getId().toString());

            verify(wireMockService, never()).publishStub(any());
        }

        @Test
        @DisplayName("publishes when present both in DB and WireMock")
        void happyPath() throws Exception {
            var p = project("p");
            var m = mock(p, "n");
            when(mockRepository.findById(m.getId())).thenReturn(Optional.of(m));

            service.republish(m.getId());

            verify(wireMockService).publishStub(any(WiremockReq.class));
        }
    }
}
