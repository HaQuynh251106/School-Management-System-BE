package com.sse.app.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {
    @Mock AuditLogRepository repo;
    @Mock ObjectProvider<MongoAuditSink> provider;
    @Mock MongoAuditSink sink;

    @Test
    void postgresAuditSurvivesMongoOutage() {
        AuditLog saved = AuditLog.builder().id("evt-1").createdAt(Instant.now()).build();
        when(repo.save(any())).thenReturn(saved);
        when(provider.getIfAvailable()).thenReturn(sink);
        doThrow(new IllegalStateException("mongo offline")).when(sink).append(saved);

        new AuditService(repo, provider).record("admin", "Admin", "ADMIN",
                "UPDATE", "identity", "USER", "u-1", "test");

        verify(repo).save(any());
        verify(sink).append(saved);
    }

    @Test
    void backfillIsIdempotentlyDelegatedToMongoSink() {
        List<AuditLog> rows = List.of(AuditLog.builder().id("evt-1").build(),
                AuditLog.builder().id("evt-2").build());
        when(provider.getIfAvailable()).thenReturn(sink);
        when(repo.findTop1000ByOrderByCreatedAtDesc()).thenReturn(rows);
        when(sink.sync(rows)).thenReturn(2);

        var result = new AuditService(repo, provider).syncMongo();

        assertEquals(2, result.get("synced"));
        verify(sink).sync(rows);
    }

    @Test
    void reportsMongoDisabledWithoutBreakingAuditApi() {
        when(provider.getIfAvailable()).thenReturn(null);
        var status = new AuditService(repo, provider).mongoStatus();
        assertFalse((Boolean) status.get("enabled"));
    }
}
