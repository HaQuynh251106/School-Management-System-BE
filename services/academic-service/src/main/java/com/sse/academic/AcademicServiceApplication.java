package com.sse.academic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point của Academic Service.
 *
 * P2 + P3 cùng commit vào service này — xem docs/team/TEAM-ASSIGNMENT.md §3.2/3.3
 * để biết file nào của ai.
 *
 * P2 sẽ thêm entity đầu tiên: AcademicYear, Semester, Class, ...
 * P3 sẽ thêm entity: Grade, Assignment, ...
 */
@SpringBootApplication
public class AcademicServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AcademicServiceApplication.class, args);
    }
}
