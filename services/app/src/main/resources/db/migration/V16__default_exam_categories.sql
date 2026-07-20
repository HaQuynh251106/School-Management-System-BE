-- Cấu hình nghiệp vụ tối thiểu để sổ điểm có thể dựng đủ các cột.
-- Đây là dữ liệu cấu hình hệ thống, không phải dữ liệu người dùng/demo.
INSERT INTO exam_categories (id, code, name, weight, required_count)
SELECT 'ec-oral', 'ORAL', 'Miệng', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM exam_categories WHERE code = 'ORAL');

INSERT INTO exam_categories (id, code, name, weight, required_count)
SELECT 'ec-15m', '15M', '15 phút', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM exam_categories WHERE code = '15M');

INSERT INTO exam_categories (id, code, name, weight, required_count)
SELECT 'ec-mid', 'MID', 'Giữa kỳ', 2, 1
WHERE NOT EXISTS (SELECT 1 FROM exam_categories WHERE code = 'MID');

INSERT INTO exam_categories (id, code, name, weight, required_count)
SELECT 'ec-final', 'FINAL', 'Cuối kỳ', 3, 1
WHERE NOT EXISTS (SELECT 1 FROM exam_categories WHERE code = 'FINAL');
