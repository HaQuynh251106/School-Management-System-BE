package com.sse.app.seed;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents demo credentials printed by the reset script from drifting from SQL hashes. */
class FullDemoPasswordHashTest {
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @Test
    void representativePasswordsUseValidBcryptHashes() {
        assertTrue(bcrypt.matches("Admin@123",
                "$2a$10$uiIN7ah2rIcamZdyUH/yM.KX3IuQ70t80d.OC3KibBIH2MfKhPu2."));
        assertTrue(bcrypt.matches("Teacher@123",
                "$2a$10$/ka71A3CXkDW/g9swoW8PuV.lCHj2GTLJ1.cHW3k6KmIiUMweLtEy"));
        assertTrue(bcrypt.matches("Student@123",
                "$2a$10$F3g3JAvND2cU2O9VXo8.1OR6AlBTGi.wLWkpnJ7e4wHxUkuDOtL.a"));
        assertTrue(bcrypt.matches("Parent@123",
                "$2a$10$EIANrs2dAzHTvt5x957bLe2C7eVWgTiQokvlVuDMJ/1WrOKnr82Gu"));
        assertFalse(bcrypt.matches("Teacher@123",
                "$2a$10$uiIN7ah2rIcamZdyUH/yM.KX3IuQ70t80d.OC3KibBIH2MfKhPu2."));
    }
}
