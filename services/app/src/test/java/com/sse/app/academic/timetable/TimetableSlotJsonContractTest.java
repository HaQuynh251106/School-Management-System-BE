package com.sse.app.academic.timetable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimetableSlotJsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishedSlotIncludesRequiredLockedFlag() {
        TimetableSlot slot = TimetableSlot.builder()
                .id("slot-1")
                .sourceScheduleId("published-schedule-1")
                .build();

        JsonNode json = mapper.valueToTree(slot);

        assertTrue(json.has("locked"));
        assertTrue(json.get("locked").asBoolean());
    }

    @Test
    void legacyManualSlotIncludesUnlockedFlag() {
        TimetableSlot slot = TimetableSlot.builder()
                .id("slot-legacy")
                .build();

        JsonNode json = mapper.valueToTree(slot);

        assertTrue(json.has("locked"));
        assertFalse(json.get("locked").asBoolean());
    }
}
