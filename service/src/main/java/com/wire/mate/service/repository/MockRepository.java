package com.wire.mate.service.repository;

import com.wire.mate.service.entity.Mock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface MockRepository extends JpaRepository<Mock, UUID> {

    boolean existsByNameAndProjectId(String name, UUID projectId);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM mocks
                WHERE project_id = :projectId
                  AND (request_definition ->> 'urlPath' = :url
                       OR request_definition ->> 'url' = :url)
            )
            """, nativeQuery = true)
    boolean existsByUrlAndProjectId(@Param("url") String url, @Param("projectId") UUID projectId);

    List<Mock> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    List<Mock> findByProjectId(UUID projectId);

    @Query("SELECT m.id FROM Mock m")
    Set<UUID> findAllIds();
}
