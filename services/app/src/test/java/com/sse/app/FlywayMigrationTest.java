package com.sse.app;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlywayMigrationTest {

    @Test
    void migrationsBuildAnEmptyDatabase() {
        Flyway flyway = Flyway.configure()
                .dataSource("jdbc:h2:mem:flyway;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")
                .locations("classpath:db/migration")
                .load();

        MigrateResult result = flyway.migrate();

        assertEquals(result.migrationsExecuted, flyway.info().applied().length);
        assertEquals(0, flyway.info().pending().length);
    }

    @Test
    void upgradingPastRoleCompatibilityMigrationsPreservesDedicatedAccounts() throws Exception {
        String url = "jdbc:h2:mem:role-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway beforeRoleMigrations = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("56")
                .load();
        beforeRoleMigrations.migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement("""
                     INSERT INTO users (id, username, password_hash, full_name, role, status)
                     VALUES (?, ?, 'encoded-password', ?, ?, 'ACTIVE')
                     """)) {
            insertRoleAccount(statement, "u-academic-staff-1", "giaovu", "Giáo vụ", "ACADEMIC_STAFF");
            insertRoleAccount(statement, "u-accountant-1", "ketoan", "Kế toán", "ACCOUNTANT");
        }

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM users WHERE id = ? AND role = ?")) {
            assertRoleAccount(statement, "u-academic-staff-1", "ACADEMIC_STAFF");
            assertRoleAccount(statement, "u-accountant-1", "ACCOUNTANT");
        }
    }

    private static void insertRoleAccount(java.sql.PreparedStatement statement,
                                          String id, String username,
                                          String fullName, String role) throws Exception {
        statement.setString(1, id);
        statement.setString(2, username);
        statement.setString(3, fullName);
        statement.setString(4, role);
        statement.executeUpdate();
    }

    private static void assertRoleAccount(java.sql.PreparedStatement statement,
                                          String id, String role) throws Exception {
        statement.setString(1, id);
        statement.setString(2, role);
        try (var result = statement.executeQuery()) {
            result.next();
            assertEquals(1, result.getInt(1));
        }
    }
}
