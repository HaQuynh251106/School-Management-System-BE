package com.sse.app.academic.teaching;

import com.sse.app.academic.timetable.TimetableService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class TeachingAssignmentBootstrap {

    @Bean
    ApplicationRunner teachingAssignmentBackfillRunner(TimetableService timetable,
                                                       TeachingAssignmentService assignments) {
        return args -> {
            int created = assignments.backfillFromTimetable(timetable.allSlots());
            if (created > 0) {
                log.info("[teaching] backfilled {} teacher_class_subjects from timetable slots", created);
            }
        };
    }
}
