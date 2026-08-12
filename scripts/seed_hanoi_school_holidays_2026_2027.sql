BEGIN;

CREATE TEMP TABLE hanoi_holiday_periods (
    code varchar(80) PRIMARY KEY,
    title varchar(255) NOT NULL,
    description varchar(255) NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    priority varchar(30) NOT NULL
) ON COMMIT DROP;

INSERT INTO hanoi_holiday_periods (code, title, description, start_date, end_date, priority) VALUES
    ('national-day-2026',
     'Nghỉ lễ Quốc khánh 2026',
     'Nghỉ từ 29/08 đến hết 02/09/2026 theo lịch Quốc khánh đã được công bố; không xếp lịch và không điểm danh.',
     DATE '2026-08-29', DATE '2026-09-02', 'IMPORTANT'),
    ('new-year-2027',
     'Nghỉ Tết Dương lịch 2027',
     'Nghỉ Tết Dương lịch ngày 01/01/2027 theo quy định về ngày nghỉ lễ; không xếp lịch và không điểm danh.',
     DATE '2027-01-01', DATE '2027-01-01', 'IMPORTANT'),
    ('hung-kings-2027',
     'Nghỉ Giỗ Tổ Hùng Vương 2027',
     'Nghỉ Giỗ Tổ Hùng Vương (10/03 Âm lịch) ngày 16/04/2027; không xếp lịch và không điểm danh.',
     DATE '2027-04-16', DATE '2027-04-16', 'IMPORTANT'),
    ('reunification-labour-2027',
     'Nghỉ lễ 30/04 và Quốc tế Lao động 2027',
     'Nghỉ ngày 30/04, 01/05 và ngày nghỉ bù 03/05/2027; không xếp lịch và không điểm danh.',
     DATE '2027-04-30', DATE '2027-05-03', 'IMPORTANT');

-- Calendar data: one row per date so every dashboard can render the whole holiday range.
INSERT INTO school_holidays (id, date, name, description)
SELECT
    'hn-holiday-' || to_char(day_value, 'YYYYMMDD'),
    day_value::date,
    period.title,
    period.description
FROM hanoi_holiday_periods period
CROSS JOIN LATERAL generate_series(period.start_date, period.end_date, interval '1 day') day_value
ON CONFLICT (date) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description;

-- Official school-wide announcements also drive attendance locking.
INSERT INTO announcements (
    id, created_at, body, audience, created_by, title, category, priority,
    status, recipient_count, holiday_start_date, holiday_end_date, sent_at
)
SELECT
    'announcement-hanoi-' || period.code,
    now(),
    period.description,
    'ALL',
    (SELECT id FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE' ORDER BY created_at NULLS LAST LIMIT 1),
    period.title,
    'HOLIDAY_EVENT',
    period.priority,
    'SENT',
    (SELECT count(*)::int FROM users WHERE status = 'ACTIVE'),
    period.start_date,
    period.end_date,
    now()
FROM hanoi_holiday_periods period
ON CONFLICT (id) DO UPDATE SET
    body = EXCLUDED.body,
    title = EXCLUDED.title,
    priority = EXCLUDED.priority,
    status = 'SENT',
    recipient_count = EXCLUDED.recipient_count,
    holiday_start_date = EXCLUDED.holiday_start_date,
    holiday_end_date = EXCLUDED.holiday_end_date,
    sent_at = EXCLUDED.sent_at;

-- Fan out in-app notifications idempotently to all active accounts.
INSERT INTO notifications (
    id, read, created_at, body, recipient_id, ref_id, ref_type, title,
    type, priority, sent_at, delivered_at
)
SELECT
    'notification-' || md5(active_user.id || ':' || period.code),
    false,
    now(),
    period.description,
    active_user.id,
    'announcement-hanoi-' || period.code,
    'ANNOUNCEMENT',
    period.title,
    'HOLIDAY_EVENT',
    period.priority,
    now(),
    now()
FROM users active_user
CROSS JOIN hanoi_holiday_periods period
WHERE active_user.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1
      FROM notifications existing
      WHERE existing.recipient_id = active_user.id
        AND existing.ref_id = 'announcement-hanoi-' || period.code
  );

COMMIT;
