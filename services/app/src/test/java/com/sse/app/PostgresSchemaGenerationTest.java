package com.sse.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that Hibernate can derive a PostgreSQL schema from all mapped entities.
 * The generated script is useful when reviewing Flyway migration drift.
 */
@SpringBootTest(properties = {
        "sse.seed.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.jakarta.persistence.schema-generation.database.action=none",
        "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create",
        "spring.jpa.properties.jakarta.persistence.schema-generation.create-source=metadata",
        "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=target/postgresql-schema.sql"
})
@ActiveProfiles("local")
class PostgresSchemaGenerationTest {
    @Test
    void mappedEntitiesProduceAPostgresSchema() {
        // Context startup performs and validates schema script generation.
    }
}
