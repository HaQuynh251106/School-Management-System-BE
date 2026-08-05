-- Với ngày học chỉ có 5 tiết, giáo viên tải cao cần được phép dạy đủ 5 tiết liền mạch.
-- Các khung bận tuyệt đối vẫn luôn được thuật toán tôn trọng.
update teacher_load_registrations
set max_daily_periods = 5,
    max_consecutive_periods = 5
where semester_id in (
    select sem.id from semesters sem where sem.status in ('ACTIVE', 'PLANNED')
);
