CREATE UNIQUE INDEX IF NOT EXISTS uq_classes_year_homeroom_teacher
    ON classes (academic_year_id, homeroom_teacher_id);
