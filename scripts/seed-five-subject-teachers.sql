BEGIN;

INSERT INTO subjects (id, code, name, coefficient) VALUES
    ('sj-ngu-van',   'NGU_VAN',   'Ngữ văn',   1),
    ('sj-tieng-anh', 'TIENG_ANH', 'Tiếng Anh', 1),
    ('sj-vat-ly',    'VAT_LY',    'Vật lý',    1),
    ('sj-hoa-hoc',   'HOA_HOC',   'Hóa học',   1),
    ('sj-tin-hoc',   'TIN_HOC',   'Tin học',   1)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    coefficient = EXCLUDED.coefficient;

INSERT INTO users (
    id, username, full_name, role, status, password_hash,
    teacher_code, main_subject, email, phone,
    password_change_required, token_version, created_at
) VALUES
    (
        'u-teacher-ngu-van', 'gv.nguvan', 'Trần Thu Hà', 'TEACHER', 'ACTIVE',
        '$2y$10$.UVWEQMzbQCUC5GSQgaq5O7aZDfNdImnuEYWHIbdKnFf7/RDK838G',
        'GV101', 'Ngữ văn', 'thuha.tran@sse.edu.vn', '0902101101', false, 0, current_timestamp
    ),
    (
        'u-teacher-tieng-anh', 'gv.tienganh', 'Lê Hoàng Anh', 'TEACHER', 'ACTIVE',
        '$2y$10$.UVWEQMzbQCUC5GSQgaq5O7aZDfNdImnuEYWHIbdKnFf7/RDK838G',
        'GV102', 'Tiếng Anh', 'hoanganh.le@sse.edu.vn', '0902101102', false, 0, current_timestamp
    ),
    (
        'u-teacher-vat-ly', 'gv.vatly', 'Phạm Quốc Bảo', 'TEACHER', 'ACTIVE',
        '$2y$10$.UVWEQMzbQCUC5GSQgaq5O7aZDfNdImnuEYWHIbdKnFf7/RDK838G',
        'GV103', 'Vật lý', 'quocbao.pham@sse.edu.vn', '0902101103', false, 0, current_timestamp
    ),
    (
        'u-teacher-hoa-hoc', 'gv.hoahoc', 'Nguyễn Ngọc Lan', 'TEACHER', 'ACTIVE',
        '$2y$10$.UVWEQMzbQCUC5GSQgaq5O7aZDfNdImnuEYWHIbdKnFf7/RDK838G',
        'GV104', 'Hóa học', 'ngoclan.nguyen@sse.edu.vn', '0902101104', false, 0, current_timestamp
    ),
    (
        'u-teacher-tin-hoc', 'gv.tinhoc', 'Đỗ Minh Khang', 'TEACHER', 'ACTIVE',
        '$2y$10$.UVWEQMzbQCUC5GSQgaq5O7aZDfNdImnuEYWHIbdKnFf7/RDK838G',
        'GV105', 'Tin học', 'minhkhang.do@sse.edu.vn', '0902101105', false, 0, current_timestamp
    )
ON CONFLICT (username) DO UPDATE SET
    full_name = EXCLUDED.full_name,
    role = EXCLUDED.role,
    status = EXCLUDED.status,
    teacher_code = EXCLUDED.teacher_code,
    main_subject = EXCLUDED.main_subject,
    email = EXCLUDED.email,
    phone = EXCLUDED.phone,
    password_hash = EXCLUDED.password_hash,
    password_change_required = EXCLUDED.password_change_required;

COMMIT;
