package com.wire.mate.service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Postgres container for integration tests.
 *
 * <p>{@link ServiceConnection} wires the container into Spring Boot's
 * {@code DataSource} auto-config — no manual URL/user/password properties are
 * needed. The container is reusable so it survives across multiple test
 * classes in the same JVM.</p>
 *
 * <p>Import this class into any test that needs a real database via
 * {@code @Import(PostgresTestContainerConfig.class)}.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainerConfig {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("wiremate")
                .withUsername("wiremate")
                .withPassword("wiremate")
                .withReuse(true);
    }
}
