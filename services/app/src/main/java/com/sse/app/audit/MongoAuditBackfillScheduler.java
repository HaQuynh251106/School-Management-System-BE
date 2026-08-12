package com.sse.app.audit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically fills Mongo gaps after a temporary Mongo outage. */
@Component
@ConditionalOnBean(MongoAuditSink.class)
public class MongoAuditBackfillScheduler {
    private final AuditService audit;

    public MongoAuditBackfillScheduler(AuditService audit) {
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${sse.audit.mongo.sync-delay-ms:60000}")
    public void sync() {
        try {
            audit.syncMongo();
        } catch (RuntimeException ignored) {
            // PostgreSQL remains the source of truth; the next run retries the backfill.
        }
    }
}
