package com.wire.mate.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test — boots the full Spring context against a Testcontainers Postgres
 * to catch wiring issues (missing beans, bad bean dependencies, broken Flyway
 * migrations, mismatched entity ↔ schema, etc.) before a slower integration
 * test would.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class ServiceApplicationTests {

    @Test
    void contextLoads() {
        // The fact that the context boots is the assertion.
    }
}
