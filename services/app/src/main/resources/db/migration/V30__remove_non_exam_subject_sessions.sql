-- Chào cờ và Sinh hoạt lớp là hoạt động cố định của thời khóa biểu, không phải môn thi.
-- Các phòng thi và danh sách học sinh liên quan được xóa theo khóa ngoại ON DELETE CASCADE.
DELETE FROM exam_sessions session
USING subjects subject
WHERE session.subject_id = subject.id
  AND UPPER(TRIM(subject.code)) IN ('FLAG', 'HOMEROOM', 'CHAOCO', 'SHL');
