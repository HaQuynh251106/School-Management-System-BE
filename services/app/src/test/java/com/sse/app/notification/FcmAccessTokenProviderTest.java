package com.sse.app.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FcmAccessTokenProviderTest {
    @Test
    void staticAccessTokenIsUsedWithoutServiceAccount() throws Exception {
        FcmAccessTokenProvider provider = new FcmAccessTokenProvider("token-123", "");

        assertTrue(provider.isConfigured());
        assertEquals("STATIC_ACCESS_TOKEN", provider.source());
        assertEquals("token-123", provider.accessToken());
    }

    @Test
    void missingCredentialIsReportedClearly() {
        FcmAccessTokenProvider provider = new FcmAccessTokenProvider("", "");

        assertFalse(provider.isConfigured());
        assertEquals("NOT_CONFIGURED", provider.source());
        assertThrows(IllegalStateException.class, provider::accessToken);
    }
}
