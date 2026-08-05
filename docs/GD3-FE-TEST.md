# Luong test FE - Giai doan 3

## 1. Chuan bi

- Backend: `http://127.0.0.1:4000`
- Frontend: `http://127.0.0.1:5173`
- Admin: `admin` / `admin@123`
- Giao vien Toan: `gv.toan` / `teacher@123`
- Hoc sinh lop 10A1: `hs.thao` / `student@123`
- Phu huynh cua hoc sinh tren: `ph.vu` / `parent@123`

Du lieu hien tai co ke hoach khoi 10 phien ban 3 da cong bo va phien ban 4 dang o trang thai ban nhap. Dung phien ban 4 de sua du lieu; dung phien ban 3 de kiem tra che do chi doc va snapshot da cong bo.

## 2. Chuong trinh giao duc

1. Dang nhap Admin, mo `Co cau dao tao` > `Chuong trinh`.
2. Kiem tra chi `GDPT2018` co trang thai `Dang ap dung`; chuong trinh `TEST` la `Da luu tru`.
3. Chon khoi 10 va tim mon `Sinh hoc`.
4. Sua HK1 tu 35 thanh 36. Cot `Ca nam` phai tu doi tu 70 thanh 71.
5. Sua HK1 lai 35 va bam nut luu tren dong Sinh hoc.
6. Bam `Tao chuong trinh khac`, nhap ma va ten, sau do tao.
7. Chuong trinh moi phai la `Ban nhap`, form tao phai dong va xoa noi dung vua nhap.
8. Khi bam `Ap dung chuong trinh`, chuong trinh cu tu chuyen sang `Da luu tru`.
9. Tai moi trang va kiem tra van chi co mot chuong trinh `Dang ap dung`.

## 3. To hop mon lua chon

1. Mo tab `To hop mon`.
2. Dau trang phai ghi ro `Nam hoc dang ap dung: Nam hoc 2027-2028`.
3. Chon khoi 10. KHTN phai hien lop 10A1-10A5; KHXH hien 10A6-10A10.
4. Tich 10A1 o KHXH. Dau tich 10A1 o KHTN phai tu bo ngay.
5. Tich lai 10A1 o KHTN va bam `Luu danh sach lop`.
6. Tai moi trang: 10A1 chi duoc hien trong KHTN, khong duoc dong thoi nam trong KHXH.
7. Tao mot to hop moi. Sau khi luu, form tao phai dong va xoa ten/ma/mon da nhap.

## 4. Luong lap ke hoach giao duc

Mo `Ke hoach giao duc nam hoc`, chon nam 2027-2028, khoi 10. Man hinh duoc chia thanh 5 nut:

1. `Tong quan va mon hoc`: chon chuong trinh, tao ban nhap, khoi tao mon va kiem tra HK1 + HK2.
2. `Noi dung mon hoc`: nhap giai doan, chuong, chu de, bai hoc, tuan kiem tra va tuan du phong.
3. `Phan phoi theo tuan`: chia so tiet tung mon theo tuan va loai noi dung.
4. `Kiem tra va danh gia`: lap ke hoach theo hoc ky, mon, ten bai, loai, hinh thuc, tuan, thoi luong, pham vi, cach ghi nhan ket qua va noi dung bai hoc duoc danh gia.
5. `Duyet va cong bo`: xem loi/canh bao theo nhom, bam di toi dung buoc can sua, gui duyet, ra soat, phe duyet va cong bo.

### Tao ke hoach moi

1. Chon khoi chua co ke hoach, chon chuong trinh `GDPT2018`, nhap ten va bam `Tao ke hoach`.
2. Tai buoc 1, bam `Khoi tao tu chuong trinh`.
3. He thong phai sinh hai dong HK1/HK2 cho moi mon cua khoi.
4. Bam khoi tao lan hai: khong duoc sinh trung mon, bai hoc, phan phoi hay ke hoach kiem tra.
5. Kiem tra bang tong hop: moi dong phai co `Da khop`.

### Hoan thien va cong bo

1. Mo lan luot buoc 2-4 de kiem tra/sua noi dung.
2. Tai buoc 3, cuon xuong danh sach va bam nut but chi tren mot dong. Trang phai tu cuon len form, nap du thong tin cua dong da chon va hien nut `Luu chinh sua`. Cot `So tiet` trong bang chi hien gia tri, khong sua truc tiep.
3. Noi dung `Ly thuyet` co the lien ket voi bai hoc o buoc 2 de truy vet, nhung khong bat buoc. Chon `Khong lien ket bai hoc` van phai luu duoc.
4. `Chao co` va `Sinh hoat lop` khong nam trong danh sach mon cua ke hoach giao duc, khong bi yeu cau chuong/bai hoc, tuan kiem tra hay tuan du phong.
5. Tai buoc 5, loi do phai bang 0 truoc khi gui duyet; canh bao vang khong chan gui. Mo tung nhom loi va bam `Di toi buoc ... de xu ly` de kiem tra dieu huong.
6. Nhap nhan xet va bam `Gui duyet`.
7. Bam `Xac nhan kiem tra`, sau do `Phe duyet` va `Cong bo`.
8. Phien ban da cong bo chi duoc xem, khong sua truc tiep. Ket qua validation cua phien ban da cong bo phai duoc giu nguyen bang snapshot.
9. Neu can sua, bam `Tao phien ban dieu chinh`; phien ban cu van duoc giu de doi chieu.
10. Doi qua lai giua cac phien ban. Khong duoc hien loi `khong tim thay phien ban`.
11. Xuat Excel/PDF tai buoc 1; file phai mo duoc va dung ke hoach dang chon.

## 5. Test theo role

### Giao vien

1. Dang nhap `gv.toan`.
2. Mo `Lop duoc phan cong`, chon mot lop giao vien dang day.
3. Phan `Ke hoach giao duc da cong bo` phai hien mon Toan cua lop do.
4. Giao vien khong duoc xem mon/lop khong duoc phan cong va khong duoc phe duyet thay Admin.

### Hoc sinh

1. Dang nhap `hs.thao`.
2. Mo `Theo doi hoc thuat` > `Ke hoach giao duc`.
3. Phai thay ke hoach khoi 10 phien ban dang duoc cong bo, lop 10A1.
4. Danh sach gom mon bat buoc va mon KHTN; khong hien cac mon chi thuoc KHXH.

### Phu huynh

1. Dang nhap `ph.vu`.
2. Chon con `Vu Phuong Thao`, mo `Theo doi hoc tap` > `Ke hoach giao duc`.
3. Du lieu phai giong hoc sinh 10A1 va chi hien ke hoach da cong bo.
4. API xem hoc sinh khong phai con minh phai tra `403`.

## 6. Kiem thu tu dong

```powershell
cd C:\SchoolManagementSystem\BE

& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" `
  -pl services/app -am `
  '-Dtest=EducationPlanningCatalogServiceTest,AcademicPlanningServiceTest' test

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-academic-g3.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

Lenh kiem tra toan bo backend hien tai phai cho ket qua `164 tests, 0 failures`:

```powershell
& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" `
  -pl services/app -am test
```

Neu ca ba khoi da co ke hoach, smoke script se bao `SKIP` de khong tao du lieu trung. Khi do dung unit test va luong FE o tren de nghiem thu Giai doan 3.

## 7. Thu tu sau Giai doan 3

1. Quay lai nghiem thu Giai doan 4: xep thoi khoa bieu tu dong cho ca ba khoi.
2. Quay lai nghiem thu Giai doan 5: lich thi, phong thi, giam thi, phien ban va cong bo.
3. Nang cap va nghiem thu lai giao dien Giao vien, Hoc sinh, Phu huynh cho toan bo du lieu da cong bo cua Giai doan 3-5. Uu tien man Giao vien vi hien tai chua the hien ro cong viec, lop, mon, hoc ky va tien do can xu ly.
4. Lam Giai doan 6 theo thu tu: cau hinh diem, don xin nghi, bai tap nang cao, ngoai khoa co phi, chat, notification provider, bao cao hoc vu va ha tang tai lieu/kiem thu tai.

Trang thai Giai doan 3 hien tai: nghiep vu loi, API, phan quyen va man Admin da hoan thanh; UX cua ba role con lai chua du dieu kien nghiem thu cuoi cung.
