package com.sse.app.audit;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Filters;
import jakarta.annotation.PreDestroy;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(name = "sse.audit.mongo.enabled", havingValue = "true")
public class MongoAuditSink {
    private final MongoClient client;
    private final MongoCollection<Document> collection;
    private final int retentionDays;
    private final long retryCooldownMs;
    private final AtomicBoolean indexesReady = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private volatile Instant lastSuccessAt;
    private volatile Instant retryAfter;

    public MongoAuditSink(@Value("${sse.audit.mongo.uri}") String uri,
                          @Value("${sse.audit.mongo.database:sse_audit}") String database,
                          @Value("${sse.audit.mongo.collection:audit_logs}") String collection,
                          @Value("${sse.audit.mongo.retention-days:365}") int retentionDays,
                          @Value("${sse.audit.mongo.connect-timeout-ms:2000}") long connectTimeoutMs,
                          @Value("${sse.audit.mongo.retry-cooldown-ms:30000}") long retryCooldownMs) {
        this(createClient(uri, connectTimeoutMs), database, collection,
                retentionDays, retryCooldownMs);
    }

    private MongoAuditSink(MongoClient client, String database, String collection,
                           int retentionDays, long retryCooldownMs) {
        this(client, client.getDatabase(database).getCollection(collection),
                retentionDays, retryCooldownMs);
    }

    MongoAuditSink(MongoClient client, MongoCollection<Document> collection,
                   int retentionDays, long retryCooldownMs) {
        this.client = client;
        this.collection = collection;
        this.retentionDays = retentionDays;
        this.retryCooldownMs = retryCooldownMs;
    }

    private static MongoClient createClient(String uri, long connectTimeoutMs) {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applyToClusterSettings(builder -> builder
                        .serverSelectionTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(builder -> builder
                        .connectTimeout((int) connectTimeoutMs, TimeUnit.MILLISECONDS))
                .build();
        return MongoClients.create(settings);
    }

    public void append(AuditLog log) {
        execute(() -> {
            ensureIndexes();
            collection.replaceOne(Filters.eq("_id", log.getId()), document(log),
                    new ReplaceOptions().upsert(true));
        });
    }

    public int sync(List<AuditLog> logs) {
        execute(() -> {
            ensureIndexes();
            for (AuditLog log : logs) {
                collection.replaceOne(Filters.eq("_id", log.getId()), document(log),
                        new ReplaceOptions().upsert(true));
            }
        });
        return logs.size();
    }

    public Map<String, Object> status() {
        try {
            Document ping = client.getDatabase("admin").runCommand(new Document("ping", 1));
            lastSuccessAt = Instant.now();
            retryAfter = null;
            lastError.set(null);
            return Map.of("enabled", true, "connected", ping.getDouble("ok") == 1.0,
                    "indexesReady", indexesReady.get(), "retentionDays", retentionDays,
                    "lastSuccessAt", lastSuccessAt.toString());
        } catch (Exception exception) {
            lastError.set(exception.getMessage());
            return Map.of("enabled", true, "connected", false,
                    "indexesReady", indexesReady.get(), "retentionDays", retentionDays,
                    "lastError", safeMessage(exception));
        }
    }

    private Document document(AuditLog log) {
        return new Document("_id", log.getId())
                .append("actorId", log.getActorId()).append("actorName", log.getActorName())
                .append("role", log.getRole()).append("action", log.getAction())
                .append("module", log.getModule()).append("entityType", log.getEntityType())
                .append("entityId", log.getEntityId()).append("detail", log.getDetail())
                .append("ipAddress", log.getIpAddress()).append("userAgent", log.getUserAgent())
                .append("requestId", log.getRequestId())
                .append("createdAt", log.getCreatedAt() == null ? null : java.util.Date.from(log.getCreatedAt()));
    }

    private synchronized void ensureIndexes() {
        if (indexesReady.get()) return;
        collection.createIndex(Indexes.compoundIndex(
                Indexes.ascending("module"), Indexes.descending("createdAt")),
                new IndexOptions().name("module_created_at"));
        collection.createIndex(Indexes.compoundIndex(
                Indexes.ascending("action"), Indexes.descending("createdAt")),
                new IndexOptions().name("action_created_at"));
        collection.createIndex(Indexes.compoundIndex(
                Indexes.ascending("actorId"), Indexes.descending("createdAt")),
                new IndexOptions().name("actor_created_at"));
        if (retentionDays > 0) {
            collection.createIndex(Indexes.ascending("createdAt"),
                    new IndexOptions().name("ttl_created_at")
                            .expireAfter((long) retentionDays, TimeUnit.DAYS));
        }
        indexesReady.set(true);
    }

    private void execute(Runnable action) {
        if (retryAfter != null && Instant.now().isBefore(retryAfter)) {
            throw new IllegalStateException("Mongo audit is cooling down after a connection failure");
        }
        try {
            action.run();
            lastSuccessAt = Instant.now();
            lastError.set(null);
            retryAfter = null;
        } catch (RuntimeException exception) {
            lastError.set(safeMessage(exception));
            retryAfter = Instant.now().plusMillis(retryCooldownMs);
            throw exception;
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    @PreDestroy
    public void close() { client.close(); }
}
