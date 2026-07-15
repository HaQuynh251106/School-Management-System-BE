ALTER TABLE users ADD COLUMN date_of_birth date;
ALTER TABLE users ADD COLUMN gender varchar(32);
ALTER TABLE users ADD COLUMN place_of_birth varchar(255);
ALTER TABLE users ADD COLUMN ethnicity varchar(100);
ALTER TABLE users ADD COLUMN nationality varchar(100);
ALTER TABLE users ADD COLUMN address varchar(500);
ALTER TABLE users ADD COLUMN enrollment_date date;
ALTER TABLE users ADD COLUMN guardian_name varchar(255);
ALTER TABLE users ADD COLUMN guardian_phone varchar(50);

UPDATE users
SET date_of_birth = DATE '2010-03-18',
    gender = 'FEMALE',
    place_of_birth = 'Hà Nội',
    ethnicity = 'Kinh',
    nationality = 'Việt Nam',
    address = '12 Nguyễn Trãi, Thanh Xuân, Hà Nội',
    enrollment_date = DATE '2025-09-05',
    guardian_name = 'Phạm Văn Quân',
    guardian_phone = '0900000020'
WHERE id = 'u-student-1';

UPDATE users
SET date_of_birth = DATE '2012-08-09',
    gender = 'MALE',
    place_of_birth = 'Hà Nội',
    ethnicity = 'Kinh',
    nationality = 'Việt Nam',
    address = '12 Nguyễn Trãi, Thanh Xuân, Hà Nội',
    enrollment_date = DATE '2025-09-05',
    guardian_name = 'Phạm Văn Quân',
    guardian_phone = '0900000020'
WHERE id = 'u-student-2';
