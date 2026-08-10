package com.sse.app.audit;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import jakarta.annotation.PreDestroy;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "sse.audit.mongo.enabled", havingValue = "true")
public class MongoAuditSink {
    private final MongoClient client;
    private final MongoCollection<Document> collection;

    public MongoAuditSink(@Value("${sse.audit.mongo.uri}") String uri,
                          @Value("${sse.audit.mongo.database:sse_audit}") String database,
                          @Value("${sse.audit.mongo.collection:audit_logs}") String collection) {
        this.client = MongoClients.create(uri);
        this.collection = client.getDatabase(database).getCollection(collection);
    }

    public void append(AuditLog log) {
        collection.insertOne(new Document("_id", log.getId())
                .append("actorId", log.getActorId()).append("actorName", log.getActorName())
                .append("role", log.getRole()).append("action", log.getAction())
                .append("module", log.getModule()).append("entityType", log.getEntityType())
                .append("entityId", log.getEntityId()).append("detail", log.getDetail())
                .append("ipAddress", log.getIpAddress()).append("userAgent", log.getUserAgent())
                .append("requestId", log.getRequestId())
                .append("createdAt", log.getCreatedAt() == null ? null : java.util.Date.from(log.getCreatedAt())));
    }

    @PreDestroy
    public void close() { client.close(); }
}
