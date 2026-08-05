# Kiem thu Giai doan 5 mo rong

## Chuan bi

1. Chay PostgreSQL, RabbitMQ, MinIO va backend tai `http://127.0.0.1:4000`.
2. Chay frontend tai `http://127.0.0.1:5173`.
3. Dang nhap Admin bang `admin / admin@123`.
4. Mo **Khao thi & lich thi** > **Lich thi & coi thi**.

## 1. Thoi luong rieng cho tung mon

1. Tao mot dot thi nhap hoac chon mot phien ban `Ban nhap`.
2. Trong **Lap lich thi**, chon cac mon can thi.
3. Dat thoi luong khac nhau, vi du: Ngu van 120 phut, Toan 90 phut, Tieng Anh 60 phut.
4. Bam **Tao lich tu dong**.
5. Mo danh sach ca thi va kiem tra gio ket thuc cua tung mon.

Ket qua dat: moi mon dung thoi luong da nhap; gia tri hop le tu 15 den 300 phut. Mon bo chon khong duoc tao ca thi.

## 2. Lap lich thi thu cong

1. Tai phien ban nhap, bam **Them ca thi thu cong**.
2. Chon mon, khoi, ngay thi, gio bat dau va thoi luong.
3. Bam **Them va chia phong**.
4. Mo ca thi vua tao, kiem tra phong, hoc sinh, giam thi chinh va giam thi du phong.
5. Dung nut sua ben phai ca thi de doi ngay/gio; dung nut sua tren tung phong de doi phong hoac giam thi.
6. Bam **Kiem tra** truoc khi phat hanh.

Ket qua dat: Admin chon ca thi bang tay; he thong chi ho tro chia phong, xep hoc sinh va giam thi ban dau. Moi chinh sua duoc kiem tra lai.

## 3. Giao vien ban/nghi

1. Chon phien ban `Ban nhap`, mo tab **GV ban/nghi**.
2. Chon giao vien va ngay nam trong thoi gian cua dot thi.
3. Chon **Nghi ca ngay**, hoac bo chon va nhap **Tu gio / Den gio**.
4. Nhap ly do va bam **Ghi nhan**.
5. Quay lai tab **Lich thi**, bam **Tao lich tu dong**.
6. Mo cac phong thi cua ngay/khung gio da khai bao.

Ket qua dat: giao vien do khong xuat hien trong vai tro giam thi chinh hoac du phong tai khung gio ban/nghi.

Kiem tra voi lich da co:

1. Ghi nhan lich ban trung voi ca thi ma giao vien dang coi.
2. Bam **Kiem tra**.
3. He thong phai bao loi giam thi co lich ban; Admin doi giam thi hoac tao lai lich, sau do kiem tra lai.

## 4. Xoa dot thi

1. Tao mot dot thi nhap moi va khong phat hanh.
2. Bam **Xoa** tren khu vuc dot thi.
3. Doc canh bao va xac nhan xoa.

Ket qua dat: dot thi nhap, ca thi, phong thi va lich ban/nghi lien quan bi xoa. Dot thi da tung phat hanh khong duoc xoa de bao toan audit.

## 5. Thu hoi lich da phat hanh

1. Chon dot thi co trang thai **Da phat hanh**.
2. Bam **Thu hoi ve nhap**.
3. Nhap ly do bat buoc va xac nhan.
4. Dang nhap tai khoan hoc sinh/phu huynh/giao vien de kiem tra lich vua thu hoi khong con hien thi.
5. Admin sua lich, bam **Kiem tra**, sau do bam **Phat hanh**.
6. Kiem tra lai tai khoan nguoi dung cuoi.

Ket qua dat: ban da phat hanh chuyen thanh `Da thu hoi`, ban nhap duoc tao/giu lai de sua, lich an khoi nguoi dung cuoi va hien lai sau khi cong bo.

## 6. Smoke test backend

```powershell
cd C:\SchoolManagementSystem\BE
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-academic-g5.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

Script kiem tra tu dong: thoi luong rieng tung mon, giao vien ban/nghi, xep phong va hoc sinh, phat hanh, dieu chinh, thu hoi, cong bo lai, lich thu cong, xoa dot nhap, phan quyen, RabbitMQ va audit.
