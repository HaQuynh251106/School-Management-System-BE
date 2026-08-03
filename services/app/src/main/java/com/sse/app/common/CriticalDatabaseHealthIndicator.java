package com.sse.app.common;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Verifies that PostgreSQL is not only reachable but can also read the tables
 * that power the most important user journeys. The catalog check detects the
 * dangling pg_index condition that a normal connection probe cannot see.
 */
@Component("criticalDatabase")
public class CriticalDatabaseHealthIndicator implements HealthIndicator {
    private static final List<String> CRITICAL_TABLES = List.of(
            "users",
            "grades",
            "attendance_records",
            "class_enrollments",
            "report_cards",
            "student_yearly_summaries"
    );

    private final JdbcTemplate jdbc;

    public CriticalDatabaseHealthIndicator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            Integer danglingIndexes = jdbc.queryForObject("""
                    select count(*)
                    from pg_index i
                    left join pg_class c on c.oid = i.indexrelid
                    where c.oid is null
                    """, Integer.class);
            if (danglingIndexes != null && danglingIndexes > 0) {
                return Health.down()
                        .withDetail("reason", "PostgreSQL catalog contains dangling indexes")
                        .withDetail("danglingIndexes", danglingIndexes)
                        .build();
            }

            for (String table : CRITICAL_TABLES) {
                jdbc.queryForObject("select count(*) from " + table, Long.class);
            }
            return Health.up()
                    .withDetail("criticalTables", CRITICAL_TABLES.size())
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("reason", "A critical business table cannot be queried")
                    .build();
        }
    }
}
