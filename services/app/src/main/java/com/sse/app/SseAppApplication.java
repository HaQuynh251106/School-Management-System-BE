package com.sse.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Smart School Ecosystem — single runnable Spring Boot backend (modular monolith).
 *
 * <p>Các domain (identity, academic, attendance, grade, assignment, finance,
 * notification, extracurricular) được tổ chức thành các package độc lập trong cùng
 * một deployable. Đây là bản chạy nhanh cho GĐ1; có thể tách thành microservices sau.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class SseAppApplication {
    public static void main(String[] args) {
        SpringApplication.run(SseAppApplication.class, args);
    }
}
