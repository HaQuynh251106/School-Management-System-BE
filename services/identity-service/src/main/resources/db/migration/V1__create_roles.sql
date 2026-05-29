-- =============================================
-- Migration V1: tạo bảng roles + seed 4 role gốc
-- Tác giả: P1
-- Khi service start, Flyway sẽ tự chạy file này 1 lần duy nhất.
-- =============================================

CREATE TABLE roles (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

INSERT INTO roles (code, name, description) VALUES
    ('ADMIN',   'Quản trị viên', 'Toàn quyền hệ thống'),
    ('TEACHER', 'Giáo viên',     'Giảng dạy + nhập điểm + điểm danh'),
    ('STUDENT', 'Học sinh',      'Xem TKB, điểm, nộp bài'),
    ('PARENT',  'Phụ huynh',     'Giám sát con + thanh toán học phí');
