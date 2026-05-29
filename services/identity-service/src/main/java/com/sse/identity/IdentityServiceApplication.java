package com.sse.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point của Identity Service.
 *
 * Chạy từ IntelliJ: right-click → Run 'IdentityServiceApplication'
 * Chạy từ terminal: mvn spring-boot:run (trong thư mục services/identity-service)
 *
 * Verify đã chạy: GET http://localhost:8081/roles trả về 4 role mặc định.
 */
@SpringBootApplication
public class IdentityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
