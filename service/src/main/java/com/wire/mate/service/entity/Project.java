package com.wire.mate.service.entity;

import com.wire.mate.service.dto.project.ProjectResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "projects")
public class Project extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    public Project() {
    }

    public Project(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ProjectResponse toDto() {
        return new ProjectResponse(
                this.getId(),
                this.name,
                this.getCreatedAt()
        );
    }
}
