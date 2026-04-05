# Rule: Entities Must Be Placed in the `entity` Package

## Guideline

All JPA entities and mapped superclasses must be placed in the `entity` package, following the package-by-layer structure (see rule: package-by-layer-structure).

```
// ✅ Correct
com.example.project.entity.Project
com.example.project.entity.User
com.example.project.entity.Order
com.example.project.entity.BaseEntity

// ❌ Avoid
com.example.project.project.Project    ← feature package
com.example.project.model.Project      ← wrong layer name
com.example.project.Project            ← root package
```

## What belongs in `entity/`

- Classes annotated with `@Entity`
- Classes annotated with `@MappedSuperclass` (e.g. `BaseEntity` with auditing fields)
- Enums used exclusively as entity field types can live here too

## What does NOT belong in `entity/`

- DTOs or response records → go in `dto/` (see rule: dto-must-be-record)
- Repository interfaces → go in `repository/`
- No service logic, no controller references, no DTO imports inside entities

## Base entity example

```java
// entity/BaseEntity.java
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}

// entity/Project.java
@Entity
@Table(name = "projects")
public class Project extends BaseEntity {
    private String name;
    private String description;
    private LocalDate deadline;
}
```

## Rationale

- Consistent with the package-by-layer convention across the whole project
- Clear separation between persistence model (`entity/`) and API model (`dto/`)
- All entities are immediately discoverable in one place
