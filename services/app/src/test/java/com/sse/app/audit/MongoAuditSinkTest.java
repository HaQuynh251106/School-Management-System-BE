package com.sse.app.audit;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MongoAuditSinkTest {
    @Mock MongoClient client;
    @Mock MongoCollection<Document> collection;

    @Test
    void createsOperationalIndexesAndUsesIdempotentUpsert() {
        MongoAuditSink sink = new MongoAuditSink(client, collection, 365, 30_000);
        AuditLog log = AuditLog.builder().id("evt-1").actorId("admin")
                .module("academic").action("UPDATE").createdAt(Instant.now()).build();

        sink.append(log);
        sink.append(log);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(collection, times(4)).createIndex(any(Bson.class), options.capture());
        List<IndexOptions> indexes = options.getAllValues();
        assertTrue(indexes.stream().anyMatch(value -> "module_created_at".equals(value.getName())));
        assertTrue(indexes.stream().anyMatch(value -> "action_created_at".equals(value.getName())));
        assertTrue(indexes.stream().anyMatch(value -> "actor_created_at".equals(value.getName())));
        IndexOptions ttl = indexes.stream().filter(value -> "ttl_created_at".equals(value.getName()))
                .findFirst().orElseThrow();
        assertEquals(365L, ttl.getExpireAfter(TimeUnit.DAYS));

        ArgumentCaptor<ReplaceOptions> replace = ArgumentCaptor.forClass(ReplaceOptions.class);
        verify(collection, times(2)).replaceOne(any(Bson.class), any(Document.class), replace.capture());
        assertTrue(replace.getAllValues().stream().allMatch(ReplaceOptions::isUpsert));
    }
}
