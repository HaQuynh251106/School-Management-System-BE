package com.sse.app.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CriticalDatabaseHealthIndicatorTest {

    @Mock
    JdbcOperations jdbc;

    @Test
    void reportsUpWhenCatalogAndCriticalTablesAreReadable() {
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class)))
                .thenReturn(0);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class)))
                .thenReturn(1L);

        var health = new CriticalDatabaseHealthIndicator(jdbc).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("criticalTables", 6);
    }

    @Test
    void reportsDownWhenCatalogContainsDanglingIndexes() {
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class)))
                .thenReturn(2);

        var health = new CriticalDatabaseHealthIndicator(jdbc).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("danglingIndexes", 2);
    }

    @Test
    void reportsDownWhenCriticalTableQueryFails() {
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class)))
                .thenReturn(0);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class)))
                .thenThrow(new IllegalStateException("unreadable table"));

        var health = new CriticalDatabaseHealthIndicator(jdbc).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("reason");
    }
}
