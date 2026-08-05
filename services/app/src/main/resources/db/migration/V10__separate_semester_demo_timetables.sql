UPDATE timetable_slots
SET period_no = period_no + 100
WHERE id LIKE 'g0-slot-%';

WITH active_year AS (
    SELECT id
    FROM academic_years
    WHERE status = 'ACTIVE'
    ORDER BY start_date DESC, id
    LIMIT 1
),
ranked_classes AS (
    SELECT
        school_class.id,
        row_number() OVER (
            ORDER BY school_class.grade_level, school_class.code, school_class.id
        )::integer AS class_rank
    FROM classes school_class
    JOIN active_year year ON year.id = school_class.academic_year_id
),
ranked_subjects AS (
    SELECT
        subject.id,
        row_number() OVER (ORDER BY subject.code, subject.id)::integer
            AS subject_rank
    FROM subjects subject
),
targets AS (
    SELECT
        slot.id,
        mod(
            (school_class.class_rank - 1)
            + ((subject.subject_rank - 1) * 7)
            + ((semester.sequence - 1) * 3),
            30
        )::integer AS time_index
    FROM timetable_slots slot
    JOIN ranked_classes school_class ON school_class.id = slot.class_id
    JOIN ranked_subjects subject ON subject.id = slot.subject_id
    JOIN semesters semester ON semester.id = slot.semester_id
    WHERE slot.id LIKE 'g0-slot-%'
)
UPDATE timetable_slots slot
SET
    day_of_week = CASE (target.time_index / 6)
        WHEN 0 THEN 'MON'
        WHEN 1 THEN 'TUE'
        WHEN 2 THEN 'WED'
        WHEN 3 THEN 'THU'
        ELSE 'FRI'
    END,
    period_no = mod(target.time_index, 6) + 1,
    start_time = (ARRAY[
        '07:00','07:50','08:45','09:35','10:25','13:30'
    ])[mod(target.time_index, 6) + 1],
    end_time = (ARRAY[
        '07:45','08:35','09:30','10:20','11:10','14:15'
    ])[mod(target.time_index, 6) + 1]
FROM targets target
WHERE target.id = slot.id;
