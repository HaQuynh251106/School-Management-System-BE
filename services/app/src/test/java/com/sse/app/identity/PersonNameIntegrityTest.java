package com.sse.app.identity;

import com.sse.app.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonNameIntegrityTest {
    @Test
    void normalizesWhitespaceAndUnicode() {
        assertEquals("Nguyễn Đức Minh", PersonNameIntegrity.required("  Nguyễn   Đức Minh  "));
        assertEquals("Nguyễn", PersonNameIntegrity.required("Nguye\u0302\u0303n"));
    }

    @Test
    void rejectsIrreversiblyCorruptedNames() {
        assertThrows(ApiException.class, () -> PersonNameIntegrity.required("Nguy?n ??c Minh"));
        assertThrows(ApiException.class, () -> PersonNameIntegrity.required("Nguy\uFFFDo\uFFFDi"));
    }
}
