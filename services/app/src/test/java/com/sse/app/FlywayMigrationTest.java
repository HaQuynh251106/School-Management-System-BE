package com.sse.app;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlywayMigrationTest {

    @Test
    void migrationsBuildAnEmptyDatabase() {
        Flyway flyway = Flyway.configure()
                .dataSource("jdbc:h2:mem:flyway;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")
                .locations("classpath:db/migration")
                .load();

        assertEquals(5, flyway.migrate().migrationsExecuted);
        assertEquals(5, flyway.info().applied().length);
    }
}
