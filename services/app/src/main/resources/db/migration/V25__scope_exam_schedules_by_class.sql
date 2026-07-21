CREATE TABLE exam_schedule_classes (
    schedule_id varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL,
    CONSTRAINT pk_exam_schedule_classes PRIMARY KEY (schedule_id, class_id),
    CONSTRAINT fk_exam_schedule_class_schedule FOREIGN KEY (schedule_id)
        REFERENCES exam_schedules(id) ON DELETE CASCADE,
    CONSTRAINT fk_exam_schedule_class_class FOREIGN KEY (class_id)
        REFERENCES classes(id) ON DELETE RESTRICT
);

CREATE INDEX idx_exam_schedule_class_class ON exam_schedule_classes(class_id, schedule_id);

-- Preserve the class scope of existing schedules from their assigned candidates.
INSERT INTO exam_schedule_classes(schedule_id, class_id)
SELECT DISTINCT schedule_id, class_id
FROM exam_candidates;

-- Older schedules without candidates had no explicit class scope. Apply them to
-- all classes in the period's grade so conflicts are visible and can be corrected.
INSERT INTO exam_schedule_classes(schedule_id, class_id)
SELECT es.id, c.id
FROM exam_schedules es
JOIN exam_periods ep ON ep.id = es.exam_period_id
JOIN classes c ON ep.grade_level IS NULL OR c.grade_level = ep.grade_level
WHERE NOT EXISTS (
    SELECT 1 FROM exam_schedule_classes esc WHERE esc.schedule_id = es.id
);
