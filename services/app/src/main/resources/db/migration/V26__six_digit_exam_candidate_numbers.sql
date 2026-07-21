-- Candidate numbers are stable for a student throughout one exam period and
-- contain exactly six numeric characters.
UPDATE exam_candidates candidate
SET candidate_no = LPAD(CAST((
    SELECT COUNT(DISTINCT ranked.student_id)
    FROM exam_candidates ranked
    WHERE ranked.exam_period_id = candidate.exam_period_id
      AND ranked.student_id <= candidate.student_id
) AS varchar), 6, '0');
