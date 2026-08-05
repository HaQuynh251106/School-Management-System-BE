-- Natural keys used by idempotent imports and canonical demo data.

CREATE UNIQUE INDEX IF NOT EXISTS uk_classes_year_code
    ON public.classes (academic_year_id, lower(code))
    WHERE academic_year_id IS NOT NULL AND code IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_semesters_year_sequence
    ON public.semesters (academic_year_id, sequence)
    WHERE academic_year_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_semesters_year_code
    ON public.semesters (academic_year_id, lower(code))
    WHERE academic_year_id IS NOT NULL AND code IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_tcs_active_class_subject_semester
    ON public.teacher_class_subjects (class_id, subject_id, semester_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_grades_student_semester_subject
    ON public.grades (student_id, semester_id, subject_id);

CREATE INDEX IF NOT EXISTS idx_attendance_student_date
    ON public.attendance_records (student_id, date DESC);

CREATE INDEX IF NOT EXISTS idx_parent_student_student
    ON public.parent_student (student_id);
