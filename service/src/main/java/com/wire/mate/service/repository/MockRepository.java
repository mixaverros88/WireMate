package com.wire.mate.service.repository;

import com.wire.mate.service.entity.Mock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface MockRepository extends JpaRepository<Mock, UUID> {

    boolean existsByNameAndProjectId(String name, UUID projectId);

    List<Mock> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    /**
     * Project-only ID lookup for the reconciliation task. Avoids loading the
     * heavy {@code requestDefinition} / {@code responseDefinition} JSONB columns
     * just to compare ID sets.
     */
    @Query("SELECT m.id FROM Mock m")
    Set<UUID> findAllIds();
}
