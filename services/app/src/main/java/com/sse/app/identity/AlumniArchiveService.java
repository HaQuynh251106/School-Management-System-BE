package com.sse.app.identity;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.sse.app.academic.grade.ExamCategory;
import com.sse.app.academic.grade.Grade;
import com.sse.app.academic.grade.GradeCalculationService;
import com.sse.app.academic.grade.GradeService;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TeachingAssignmentService;
import com.sse.app.common.ApiException;
import com.sse.app.common.PageResponse;
import com.sse.app.identity.AlumniArchiveDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AlumniArchiveService {
    private static final String PERFORMANCE_SQL = """
            CASE WHEN latest.average_score IS NULL THEN 'INCOMPLETE'
                 WHEN latest.average_score>=8 THEN 'EXCELLENT'
                 WHEN latest.average_score>=6.5 THEN 'GOOD'
                 WHEN latest.average_score>=5 THEN 'AVERAGE'
                 ELSE 'WEAK' END
            """;
    private static final String COHORT_STATUS_SQL = """
            CASE WHEN c.status='ACTIVE' THEN 'ACTIVE'
                 WHEN c.id=(SELECT c2.id FROM cohorts c2 WHERE c2.status='COMPLETED'
                            ORDER BY c2.graduation_year DESC,c2.completed_at DESC NULLS LAST LIMIT 1)
                 THEN 'COMPLETED' ELSE 'ARCHIVED' END
            """;
    private static final String STUDENT_FROM = """
            FROM users u
            JOIN cohorts c ON c.id=u.cohort_id
            LEFT JOIN student_yearly_summaries latest ON latest.id=(
                SELECT s.id FROM student_yearly_summaries s
                JOIN academic_years say ON say.id=s.academic_year_id
                WHERE s.student_id=u.id
                ORDER BY say.end_date DESC NULLS LAST,s.finalized_at DESC NULLS LAST LIMIT 1
            )
            LEFT JOIN classes fc ON fc.id=COALESCE(u.graduation_class_id,latest.class_id,u.class_id)
            LEFT JOIN academic_years gay ON gay.id=u.graduation_academic_year_id
            LEFT JOIN report_cards card ON card.id=(
                SELECT rc.id FROM report_cards rc JOIN academic_years ray ON ray.id=rc.academic_year_id
                WHERE rc.student_id=u.id
                ORDER BY ray.end_date DESC NULLS LAST,rc.updated_at DESC LIMIT 1
            )
            """;

    private final JdbcTemplate jdbc;
    private final StructureService structure;
    private final TeachingAssignmentService assignments;
    private final GradeService grades;
    private final GradeCalculationService calculations;

    public List<CohortArchiveSummary> cohorts() {
        String sql = ("""
                SELECT c.id,c.code,c.name,c.entry_year,c.graduation_year,
                       %s AS archive_status,c.completed_at,
                       COUNT(u.id) student_count,
                       COUNT(u.id) FILTER (WHERE u.student_status='GRADUATED') graduated_count,
                       COUNT(u.id) FILTER (WHERE latest.promotion_status='RETAINED') retained_count,
                       COUNT(u.id) FILTER (WHERE u.student_status IN ('TRANSFERRED','WITHDRAWN')) transferred_count,
                       AVG(latest.average_score) average_score,
                       COUNT(u.id) FILTER (WHERE latest.conduct_grade IN ('GOOD','EXCELLENT')) good_conduct_count
                %s
                WHERE u.role='STUDENT'
                GROUP BY c.id,c.code,c.name,c.entry_year,c.graduation_year,c.status,c.completed_at
                ORDER BY c.graduation_year DESC,c.entry_year DESC
                """).formatted(COHORT_STATUS_SQL, STUDENT_FROM);
        return jdbc.query(sql, this::mapCohort);
    }

    public CohortArchiveOverview overview(String cohortId) {
        CohortArchiveSummary cohort = requireCohort(cohortId);
        String sql = ("""
                SELECT COUNT(u.id) FILTER (WHERE UPPER(COALESCE(u.gender,'')) IN ('MALE','NAM')) male_count,
                       COUNT(u.id) FILTER (WHERE UPPER(COALESCE(u.gender,'')) IN ('FEMALE','NỮ','NU')) female_count,
                       COUNT(u.id) FILTER (WHERE UPPER(COALESCE(u.gender,'')) NOT IN ('MALE','NAM','FEMALE','NỮ','NU')) other_gender_count,
                       COUNT(u.id) FILTER (WHERE latest.average_score>=8) excellent_count,
                       COUNT(u.id) FILTER (WHERE latest.average_score>=6.5 AND latest.average_score<8) good_academic_count,
                       COUNT(u.id) FILTER (WHERE latest.average_score>=5 AND latest.average_score<6.5) average_academic_count,
                       COUNT(u.id) FILTER (WHERE latest.average_score<5) weak_academic_count,
                       COUNT(u.id) FILTER (WHERE latest.conduct_grade IN ('GOOD','EXCELLENT')) good_conduct_count,
                       COUNT(u.id) FILTER (WHERE latest.conduct_grade='FAIR') fair_conduct_count,
                       COUNT(u.id) FILTER (WHERE latest.conduct_grade='AVERAGE') average_conduct_count,
                       COUNT(u.id) FILTER (WHERE latest.conduct_grade='WEAK') weak_conduct_count
                %s
                WHERE u.role='STUDENT' AND u.cohort_id=?
                """).formatted(STUDENT_FROM);
        long[] counts = jdbc.queryForObject(sql, (rs, row) -> new long[]{
                rs.getLong("male_count"), rs.getLong("female_count"), rs.getLong("other_gender_count"),
                rs.getLong("excellent_count"), rs.getLong("good_academic_count"), rs.getLong("average_academic_count"),
                rs.getLong("weak_academic_count"), rs.getLong("good_conduct_count"), rs.getLong("fair_conduct_count"),
                rs.getLong("average_conduct_count"), rs.getLong("weak_conduct_count")
        }, cohortId);
        String classSql = ("""
                SELECT fc.id,fc.code,COUNT(u.id) student_count,AVG(latest.average_score) average_score
                %s
                WHERE u.role='STUDENT' AND u.cohort_id=? AND fc.id IS NOT NULL
                GROUP BY fc.id,fc.code ORDER BY fc.code
                """).formatted(STUDENT_FROM);
        List<ClassDistribution> classes = jdbc.query(classSql,
                (rs, row) -> new ClassDistribution(rs.getString("id"), rs.getString("code"),
                rs.getLong("student_count"), nullableDouble(rs, "average_score")), cohortId);
        long[] value = counts == null ? new long[11] : counts;
        return new CohortArchiveOverview(cohort, value[0], value[1], value[2], value[3], value[4], value[5],
                value[6], value[7], value[8], value[9], value[10], classes);
    }

    public PageResponse<CohortStudentListItem> students(String cohortId, String q, String finalClassId,
                                                         String graduationAcademicYearId, String finalYearResult,
                                                         String graduationResult, String academicPerformance,
                                                         String conductGrade, String recordStatus,
                                                         String sort, String direction, int page, int size) {
        requireCohort(cohortId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(10, Math.min(size, 100));
        List<Object> params = new ArrayList<>();
        String where = studentWhere(cohortId, q, finalClassId, graduationAcademicYearId, finalYearResult,
                graduationResult, academicPerformance, conductGrade, recordStatus, params);
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + STUDENT_FROM + where, Long.class, params.toArray());
        String order = sortColumn(sort) + ("desc".equalsIgnoreCase(direction) ? " DESC" : " ASC") + ",u.id ASC";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(safeSize); pageParams.add(safePage * safeSize);
        String select = ("""
                SELECT u.id,u.student_code,u.full_name,u.date_of_birth,u.gender,u.email,
                       fc.id final_class_id,fc.code final_class_code,
                       gay.id graduation_academic_year_id,gay.code graduation_academic_year_code,
                       COALESCE(latest.promotion_status,CASE WHEN u.student_status='GRADUATED' THEN 'GRADUATED' ELSE 'INCOMPLETE' END) final_year_result,
                       CASE WHEN u.student_status='GRADUATED' THEN 'GRADUATED'
                            WHEN u.student_status IN ('TRANSFERRED','WITHDRAWN') THEN u.student_status
                            ELSE 'NOT_GRADUATED' END graduation_result,
                       latest.average_score,
                       %s AS academic_performance,
                       latest.conduct_grade,COALESCE(card.status,'MISSING') record_status,
                       u.student_status,u.graduated_at
                %s
                %s ORDER BY %s LIMIT ? OFFSET ?
                """).formatted(PERFORMANCE_SQL, STUDENT_FROM, where, order);
        List<CohortStudentListItem> items = jdbc.query(select, this::mapStudent, pageParams.toArray());
        long count = total == null ? 0 : total;
        int pages = count == 0 ? 0 : (int) Math.ceil(count / (double) safeSize);
        return new PageResponse<>(items, safePage, safeSize, count, pages, safePage == 0,
                pages == 0 || safePage >= pages - 1, Map.of("total", count));
    }

    public byte[] exportExcel(String cohortId, String q, String finalClassId,
                              String graduationAcademicYearId, String finalYearResult,
                              String graduationResult, String academicPerformance,
                              String conductGrade, String recordStatus, String sort, String direction) {
        CohortArchiveSummary cohort = requireCohort(cohortId);
        List<CohortStudentListItem> rows = allStudents(cohortId, q, finalClassId, graduationAcademicYearId,
                finalYearResult, graduationResult, academicPerformance, conductGrade, recordStatus, sort, direction);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Niên khóa " + cohort.code());
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("DANH SÁCH HỌC SINH NIÊN KHÓA " + cohort.code());
            Row scope = sheet.createRow(1);
            scope.createCell(0).setCellValue("Phạm vi xuất: " + exportScope(finalClassId, rows.size()));
            String[] headers = {"STT", "Mã học sinh", "Họ và tên", "Ngày sinh", "Giới tính",
                    "Lớp cuối cấp", "Kết quả cuối năm", "Kết quả tốt nghiệp", "Điểm TB",
                    "Học lực", "Rèn luyện", "Trạng thái hồ sơ"};
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var headerFont = workbook.createFont(); headerFont.setBold(true); headerStyle.setFont(headerFont);
            Row header = sheet.createRow(3);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]); header.getCell(i).setCellStyle(headerStyle);
            }
            int rowIndex = 4;
            for (CohortStudentListItem item : rows) {
                Row row = sheet.createRow(rowIndex++); int cell = 0;
                row.createCell(cell++).setCellValue(rowIndex - 4);
                row.createCell(cell++).setCellValue(value(item.studentCode()));
                row.createCell(cell++).setCellValue(value(item.fullName()));
                row.createCell(cell++).setCellValue(item.dateOfBirth() == null ? "" : item.dateOfBirth().toString());
                row.createCell(cell++).setCellValue(label(item.gender()));
                row.createCell(cell++).setCellValue(value(item.finalClassCode()));
                row.createCell(cell++).setCellValue(label(item.finalYearResult()));
                row.createCell(cell++).setCellValue(label(item.graduationResult()));
                if (item.annualAverage() != null) row.createCell(cell++).setCellValue(item.annualAverage());
                else row.createCell(cell++).setCellValue("");
                row.createCell(cell++).setCellValue(label(item.academicPerformance()));
                row.createCell(cell++).setCellValue(label(item.conductGrade()));
                row.createCell(cell).setCellValue(label(item.recordStatus()));
            }
            int[] widths = {8, 16, 30, 15, 12, 16, 20, 20, 12, 15, 15, 20};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể tạo tệp Excel kho niên khóa", exception);
        }
    }

    public byte[] exportPdf(String cohortId, String q, String finalClassId,
                            String graduationAcademicYearId, String finalYearResult,
                            String graduationResult, String academicPerformance,
                            String conductGrade, String recordStatus, String sort, String direction) {
        CohortArchiveSummary cohort = requireCohort(cohortId);
        List<CohortStudentListItem> rows = allStudents(cohortId, q, finalClassId, graduationAcademicYearId,
                finalYearResult, graduationResult, academicPerformance, conductGrade, recordStatus, sort, direction);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 24, 24, 24, 28);
            PdfWriter.getInstance(document, output); document.open();
            Font titleFont = pdfFont(15, Font.BOLD); Font normal = pdfFont(8, Font.NORMAL);
            Font header = pdfFont(8, Font.BOLD);
            Paragraph title = new Paragraph("DANH SÁCH HỌC SINH NIÊN KHÓA " + cohort.code(), titleFont);
            title.setAlignment(Element.ALIGN_CENTER); title.setSpacingAfter(6); document.add(title);
            Paragraph scope = new Paragraph("Phạm vi: " + exportScope(finalClassId, rows.size()), normal);
            scope.setAlignment(Element.ALIGN_CENTER); scope.setSpacingAfter(12); document.add(scope);
            PdfPTable table = new PdfPTable(new float[]{.6f, 1.4f, 2.8f, 1.2f, 1.2f, 1.7f, 1.8f, 1f, 1.4f, 1.4f});
            table.setWidthPercentage(100); table.setHeaderRows(1);
            for (String value : List.of("STT", "Mã HS", "Họ và tên", "Lớp cuối", "Điểm TB",
                    "Kết quả năm", "Tốt nghiệp", "Học lực", "Rèn luyện", "Hồ sơ")) {
                PdfPCell cell = new PdfPCell(new Phrase(value, header)); cell.setPadding(5);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER); table.addCell(cell);
            }
            int index = 1;
            for (CohortStudentListItem item : rows) {
                for (String value : List.of(String.valueOf(index++), value(item.studentCode()), value(item.fullName()),
                        value(item.finalClassCode()), item.annualAverage() == null ? "—" : String.format(Locale.US, "%.1f", item.annualAverage()),
                        label(item.finalYearResult()), label(item.graduationResult()), label(item.academicPerformance()),
                        label(item.conductGrade()), label(item.recordStatus()))) {
                    PdfPCell cell = new PdfPCell(new Phrase(value, normal)); cell.setPadding(4); table.addCell(cell);
                }
            }
            document.add(table); document.close();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể tạo tệp PDF kho niên khóa", exception);
        }
    }

    @Transactional(readOnly = true)
    public StudentArchiveProfile profile(String cohortId, String studentId) {
        requireCohort(cohortId);
        String profileSql = ("""
                SELECT u.id,u.student_code,u.full_name,u.date_of_birth,u.gender,u.email,u.phone,u.address,
                       u.place_of_birth,u.ethnicity,u.nationality,c.id cohort_id,c.code cohort_code,c.name cohort_name,
                       c.entry_year,c.graduation_year,%s cohort_status,
                       fc.id final_class_id,fc.code final_class_code,gay.code graduation_year_code,
                       CASE WHEN u.student_status='GRADUATED' THEN 'GRADUATED'
                            WHEN u.student_status IN ('TRANSFERRED','WITHDRAWN') THEN u.student_status
                            ELSE 'NOT_GRADUATED' END graduation_result,
                       COALESCE(card.status,'MISSING') record_status,u.graduated_at
                %s
                WHERE u.role='STUDENT' AND u.cohort_id=? AND u.id=?
                """).formatted(COHORT_STATUS_SQL, STUDENT_FROM);
        ProfileBase base = jdbc.query(profileSql, rs -> rs.next() ? mapProfileBase(rs) : null, cohortId, studentId);
        if (base == null) throw ApiException.notFound("Hồ sơ học sinh trong niên khóa");
        List<EnrollmentHistory> enrollmentHistory = enrollments(cohortId, studentId);
        Map<String, EnrollmentHistory> enrollmentByYear = enrollmentHistory.stream()
                .collect(Collectors.toMap(EnrollmentHistory::academicYearId, Function.identity(), (a, b) -> b, LinkedHashMap::new));
        List<AcademicYearRecord> years = expectedYears(base, studentId, enrollmentByYear);
        OptionalDouble average = years.stream().map(AcademicYearRecord::annualAverage).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average();
        Double programAverage = average.isPresent() ? round(average.getAsDouble()) : null;
        return new StudentArchiveProfile(base.id(), base.studentCode(), base.fullName(), base.dateOfBirth(), base.gender(),
                base.email(), base.phone(), base.address(), base.placeOfBirth(), base.ethnicity(), base.nationality(),
                base.cohortId(), base.cohortCode(), base.cohortName(), base.entryYear(), base.graduationYear(),
                base.cohortStatus(), base.finalClassId(), base.finalClassCode(), base.graduationAcademicYearCode(),
                base.graduationResult(), base.recordStatus(), base.graduatedAt(), programAverage, enrollmentHistory, years);
    }

    private List<AcademicYearRecord> expectedYears(ProfileBase base, String studentId,
                                                     Map<String, EnrollmentHistory> enrollmentByYear) {
        List<YearRef> storedYears = jdbc.query("""
                SELECT id,code FROM academic_years
                WHERE EXTRACT(YEAR FROM start_date)>=? AND EXTRACT(YEAR FROM start_date)<?
                ORDER BY start_date
                """, (rs, row) -> new YearRef(rs.getString("id"), rs.getString("code")),
                base.entryYear(), base.graduationYear());
        Map<Integer, YearRef> byStartYear = storedYears.stream().collect(Collectors.toMap(
                item -> academicYearStart(item.code()), Function.identity(), (first, ignored) -> first));
        List<AcademicYearRecord> result = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            int startYear = base.entryYear() + index;
            YearRef year = byStartYear.getOrDefault(startYear,
                    new YearRef("missing-" + base.cohortId() + "-K" + (10 + index),
                            startYear + "-" + (startYear + 1)));
            EnrollmentHistory enrollment = enrollmentByYear.get(year.id());
            result.add(yearRecord(studentId, year, enrollment, "K" + (10 + index)));
        }
        return result;
    }

    private int academicYearStart(String code) {
        if (code == null || code.length() < 4) return -1;
        try {
            return Integer.parseInt(code.substring(0, 4));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private AcademicYearRecord yearRecord(String studentId, YearRef year, EnrollmentHistory enrollment,
                                           String fallbackGrade) {
        YearSummary summary = jdbc.query("""
                SELECT s.class_id,cl.code class_code,cl.grade_level,s.semester_one_average,s.semester_two_average,
                       s.average_score,s.conduct_grade,s.promotion_status,s.missing_requirements,
                       rc.status report_card_status,rc.verification_code,rc.published_at
                FROM student_yearly_summaries s
                LEFT JOIN classes cl ON cl.id=s.class_id
                LEFT JOIN report_cards rc ON rc.student_id=s.student_id AND rc.academic_year_id=s.academic_year_id
                WHERE s.student_id=? AND s.academic_year_id=?
                """, rs -> rs.next() ? new YearSummary(rs.getString("class_id"), rs.getString("class_code"),
                rs.getString("grade_level"), nullableDouble(rs, "semester_one_average"),
                nullableDouble(rs, "semester_two_average"), nullableDouble(rs, "average_score"),
                rs.getString("conduct_grade"), rs.getString("promotion_status"), rs.getString("missing_requirements"),
                rs.getString("report_card_status"), rs.getString("verification_code"),
                instant(rs, "published_at")) : null, studentId, year.id());
        String classId = summary == null ? enrollment == null ? null : enrollment.classId() : summary.classId();
        String classCode = summary == null ? enrollment == null ? null : enrollment.classCode() : summary.classCode();
        String grade = summary == null || summary.gradeLevel() == null ?
                enrollment == null || enrollment.gradeLevel() == null ? fallbackGrade : enrollment.gradeLevel() : summary.gradeLevel();
        List<SubjectYearResult> subjects = classId == null ? List.of() : subjectResults(studentId, year.id(), classId);
        return new AcademicYearRecord(year.id(), year.code(), grade, classId, classCode,
                summary == null ? null : summary.semesterOneAverage(), summary == null ? null : summary.semesterTwoAverage(),
                summary == null ? null : summary.annualAverage(), performance(summary == null ? null : summary.annualAverage()),
                summary == null ? null : summary.conductGrade(), summary == null ? "MISSING" : summary.promotionStatus(),
                summary == null ? "Chưa có dữ liệu tổng kết năm" : summary.missingRequirements(),
                summary == null || summary.reportCardStatus() == null ? "MISSING" : summary.reportCardStatus(),
                summary == null ? null : summary.verificationCode(),
                summary == null || summary.publishedAt() == null ? null : summary.publishedAt().toString(),
                attendance(studentId, year.id()), subjects);
    }

    private List<SubjectYearResult> subjectResults(String studentId, String academicYearId, String classId) {
        List<Semester> semesters = structure.listSemesters(academicYearId);
        Semester first = semesters.stream().filter(item -> item.getSequence() == 1).findFirst().orElse(null);
        Semester second = semesters.stream().filter(item -> item.getSequence() == 2).findFirst().orElse(null);
        LinkedHashMap<String, String> subjectNames = new LinkedHashMap<>();
        if (first != null) assignments.assignmentsOfClass(classId, first.getId())
                .forEach(item -> subjectNames.put(item.getSubjectId(), item.getSubjectName()));
        if (second != null) assignments.assignmentsOfClass(classId, second.getId())
                .forEach(item -> subjectNames.put(item.getSubjectId(), item.getSubjectName()));
        List<ExamCategory> categories = grades.listCategories();
        List<Grade> all = grades.list(studentId, null, null, null, null);
        return subjectNames.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(entry -> {
            SemesterScore one = semesterScore(all, categories, first, entry.getKey());
            SemesterScore two = semesterScore(all, categories, second, entry.getKey());
            Double annual = one.average() == null || two.average() == null ? null
                    : round((one.average() + 2 * two.average()) / 3d);
            return new SubjectYearResult(entry.getKey(), entry.getValue(), one.average(), two.average(), annual,
                    one.complete() && two.complete());
        }).toList();
    }

    private SemesterScore semesterScore(List<Grade> all, List<ExamCategory> categories,
                                        Semester semester, String subjectId) {
        if (semester == null) return new SemesterScore(null, false);
        List<Grade> entries = all.stream().filter(item -> semester.getId().equals(item.getSemesterId())
                && subjectId.equals(item.getSubjectId())).toList();
        boolean complete = categories.stream().allMatch(category -> {
            Set<Integer> indexes = entries.stream().filter(item -> category.getCode().equals(item.getCategory()))
                    .filter(item -> item.getScore() != null)
                    .map(item -> item.getAssessmentIndex() == null ? 1 : item.getAssessmentIndex())
                    .collect(Collectors.toSet());
            return java.util.stream.IntStream.rangeClosed(1, Math.max(1, category.getRequiredCount()))
                    .allMatch(indexes::contains);
        });
        return new SemesterScore(complete ? calculations.subjectAverage(entries, categories) : null, complete);
    }

    private AttendanceYearSummary attendance(String studentId, String academicYearId) {
        Map<String, Integer> values = jdbc.query("""
                SELECT ar.status,COUNT(*) total FROM attendance_records ar
                JOIN academic_years ay ON ay.id=?
                WHERE ar.student_id=? AND ar.date BETWEEN ay.start_date AND ay.end_date GROUP BY ar.status
                """, rs -> {
            Map<String, Integer> map = new HashMap<>();
            while (rs.next()) map.put(rs.getString("status"), rs.getInt("total"));
            return map;
        }, academicYearId, studentId);
        int present = values.getOrDefault("PRESENT", 0);
        int excused = values.getOrDefault("EXCUSED", values.getOrDefault("EXCUSED_ABSENCE", 0));
        int unexcused = values.getOrDefault("ABSENT", values.getOrDefault("UNEXCUSED_ABSENCE", 0));
        int late = values.getOrDefault("LATE", 0);
        return new AttendanceYearSummary(present, excused, unexcused, late, present + excused + unexcused + late);
    }

    private List<EnrollmentHistory> enrollments(String cohortId, String studentId) {
        return jdbc.query("""
                SELECT e.academic_year_id,ay.code academic_year_code,e.class_id,cl.code class_code,
                       cl.grade_level,e.status,e.enrolled_at,e.ended_at
                FROM class_enrollments e JOIN academic_years ay ON ay.id=e.academic_year_id
                JOIN classes cl ON cl.id=e.class_id
                WHERE e.student_id=? AND (e.cohort_id=? OR cl.cohort_id=?)
                ORDER BY ay.start_date,e.enrolled_at
                """, (rs, row) -> new EnrollmentHistory(rs.getString("academic_year_id"),
                rs.getString("academic_year_code"), rs.getString("class_id"), rs.getString("class_code"),
                rs.getString("grade_level"), rs.getString("status"), instant(rs, "enrolled_at"),
                instant(rs, "ended_at")), studentId, cohortId, cohortId);
    }

    private String studentWhere(String cohortId, String q, String finalClassId,
                                String graduationAcademicYearId, String finalYearResult,
                                String graduationResult, String academicPerformance,
                                String conductGrade, String recordStatus, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE u.role='STUDENT' AND u.cohort_id=?");
        params.add(cohortId);
        if (q != null && !q.isBlank()) {
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(COALESCE(u.student_code,'')) LIKE ? OR LOWER(COALESCE(u.email,'')) LIKE ?)");
            String pattern = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
            params.add(pattern); params.add(pattern); params.add(pattern);
        }
        addExactFilter(where, params, "fc.id", finalClassId);
        addExactFilter(where, params, "gay.id", graduationAcademicYearId);
        addFilter(where, params, "COALESCE(latest.promotion_status,CASE WHEN u.student_status='GRADUATED' THEN 'GRADUATED' ELSE 'INCOMPLETE' END)", finalYearResult);
        if (graduationResult != null && !graduationResult.isBlank()) {
            where.append(" AND (CASE WHEN u.student_status='GRADUATED' THEN 'GRADUATED' WHEN u.student_status IN ('TRANSFERRED','WITHDRAWN') THEN u.student_status ELSE 'NOT_GRADUATED' END)=?");
            params.add(graduationResult.trim().toUpperCase(Locale.ROOT));
        }
        addFilter(where, params, PERFORMANCE_SQL, academicPerformance);
        addFilter(where, params, "COALESCE(latest.conduct_grade,'INCOMPLETE')", conductGrade);
        addFilter(where, params, "COALESCE(card.status,'MISSING')", recordStatus);
        return where.toString();
    }

    private void addFilter(StringBuilder sql, List<Object> params, String expression, String value) {
        if (value == null || value.isBlank()) return;
        sql.append(" AND (").append(expression).append(")=?");
        params.add(value.trim().toUpperCase(Locale.ROOT));
    }

    private void addExactFilter(StringBuilder sql, List<Object> params, String expression, String value) {
        if (value == null || value.isBlank()) return;
        sql.append(" AND (").append(expression).append(")=?");
        params.add(value.trim());
    }

    private String sortColumn(String sort) {
        if (sort == null) return "u.full_name";
        return switch (sort) {
            case "studentCode" -> "u.student_code";
            case "finalClass" -> "fc.code";
            case "annualAverage" -> "latest.average_score";
            case "finalYearResult" -> "latest.promotion_status";
            case "graduatedAt" -> "u.graduated_at";
            default -> "u.full_name";
        };
    }

    private List<CohortStudentListItem> allStudents(String cohortId, String q, String finalClassId,
                                                     String graduationAcademicYearId, String finalYearResult,
                                                     String graduationResult, String academicPerformance,
                                                     String conductGrade, String recordStatus,
                                                     String sort, String direction) {
        List<CohortStudentListItem> result = new ArrayList<>();
        int page = 0;
        PageResponse<CohortStudentListItem> response;
        do {
            response = students(cohortId, q, finalClassId, graduationAcademicYearId, finalYearResult,
                    graduationResult, academicPerformance, conductGrade, recordStatus, sort, direction,
                    page++, 100);
            result.addAll(response.items());
        } while (!response.last() && result.size() < 20_000);
        return result;
    }

    private Font pdfFont(float size, int style) throws Exception {
        String configured = System.getenv("SSE_PDF_FONT_PATH");
        List<String> paths = new ArrayList<>();
        if (configured != null && !configured.isBlank()) paths.add(configured);
        paths.add("C:/Windows/Fonts/arial.ttf");
        paths.add("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
        for (String path : paths) {
            if (new File(path).isFile()) {
                BaseFont base = BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                return new Font(base, size, style);
            }
        }
        return new Font(BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED), size, style);
    }

    private String exportScope(String finalClassId, int total) {
        String classScope = "toàn niên khóa";
        if (finalClassId != null && !finalClassId.isBlank()) {
            List<String> codes = jdbc.query("SELECT code FROM classes WHERE id=?",
                    (rs, row) -> rs.getString("code"), finalClassId.trim());
            classScope = "lớp " + (codes.isEmpty() ? finalClassId.trim() : codes.get(0));
        }
        return classScope + " · " + total + " học sinh · xuất lúc " + Instant.now();
    }

    private String label(String code) {
        if (code == null || code.isBlank()) return "—";
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "MALE", "NAM" -> "Nam";
            case "FEMALE", "NU", "NỮ" -> "Nữ";
            case "GRADUATED" -> "Tốt nghiệp";
            case "NOT_GRADUATED" -> "Chưa tốt nghiệp";
            case "PROMOTED" -> "Lên lớp";
            case "RETAINED" -> "Lưu ban";
            case "TRANSFERRED" -> "Chuyển trường";
            case "WITHDRAWN" -> "Thôi học";
            case "COMPLETED" -> "Hoàn thành";
            case "EXCELLENT" -> "Xuất sắc";
            case "GOOD" -> "Tốt";
            case "FAIR" -> "Khá";
            case "AVERAGE" -> "Trung bình";
            case "WEAK" -> "Yếu";
            case "PUBLISHED" -> "Đã phát hành";
            case "LOCKED" -> "Đã khóa";
            case "APPROVED" -> "Đã duyệt";
            case "DRAFT" -> "Bản nháp";
            case "MISSING", "INCOMPLETE" -> "Chưa hoàn thiện";
            default -> code;
        };
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private CohortArchiveSummary requireCohort(String cohortId) {
        return cohorts().stream().filter(item -> item.id().equals(cohortId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Niên khóa"));
    }

    private CohortArchiveSummary mapCohort(ResultSet rs, int row) throws SQLException {
        long students = rs.getLong("student_count");
        long graduates = rs.getLong("graduated_count");
        return new CohortArchiveSummary(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                (Integer) rs.getObject("entry_year"), (Integer) rs.getObject("graduation_year"),
                rs.getString("archive_status"), instant(rs, "completed_at"), students, graduates,
                rs.getLong("retained_count"), rs.getLong("transferred_count"),
                students == 0 ? 0 : Math.round(graduates * 1000d / students) / 10d,
                nullableDouble(rs, "average_score"), rs.getLong("good_conduct_count"));
    }

    private CohortStudentListItem mapStudent(ResultSet rs, int row) throws SQLException {
        return new CohortStudentListItem(rs.getString("id"), rs.getString("student_code"), rs.getString("full_name"),
                rs.getDate("date_of_birth") == null ? null : rs.getDate("date_of_birth").toLocalDate(),
                rs.getString("gender"), rs.getString("email"), rs.getString("final_class_id"),
                rs.getString("final_class_code"), rs.getString("graduation_academic_year_id"),
                rs.getString("graduation_academic_year_code"), rs.getString("final_year_result"),
                rs.getString("graduation_result"), nullableDouble(rs, "average_score"),
                rs.getString("academic_performance"), rs.getString("conduct_grade"),
                rs.getString("record_status"), rs.getString("student_status"), instant(rs, "graduated_at"));
    }

    private ProfileBase mapProfileBase(ResultSet rs) throws SQLException {
        return new ProfileBase(rs.getString("id"), rs.getString("student_code"), rs.getString("full_name"),
                rs.getDate("date_of_birth") == null ? null : rs.getDate("date_of_birth").toLocalDate(),
                rs.getString("gender"), rs.getString("email"), rs.getString("phone"), rs.getString("address"),
                rs.getString("place_of_birth"), rs.getString("ethnicity"), rs.getString("nationality"),
                rs.getString("cohort_id"), rs.getString("cohort_code"), rs.getString("cohort_name"),
                (Integer) rs.getObject("entry_year"), (Integer) rs.getObject("graduation_year"),
                rs.getString("cohort_status"), rs.getString("final_class_id"), rs.getString("final_class_code"),
                rs.getString("graduation_year_code"), rs.getString("graduation_result"),
                rs.getString("record_status"), instant(rs, "graduated_at"));
    }

    private String performance(Double score) {
        if (score == null) return "INCOMPLETE";
        if (score >= 8) return "EXCELLENT";
        if (score >= 6.5) return "GOOD";
        if (score >= 5) return "AVERAGE";
        return "WEAK";
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static double round(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private record YearRef(String id, String code) {}
    private record SemesterScore(Double average, boolean complete) {}
    private record YearSummary(String classId, String classCode, String gradeLevel,
                               Double semesterOneAverage, Double semesterTwoAverage, Double annualAverage,
                               String conductGrade, String promotionStatus, String missingRequirements,
                               String reportCardStatus, String verificationCode, Instant publishedAt) {}
    private record ProfileBase(String id, String studentCode, String fullName, java.time.LocalDate dateOfBirth,
                               String gender, String email, String phone, String address, String placeOfBirth,
                               String ethnicity, String nationality, String cohortId, String cohortCode,
                               String cohortName, Integer entryYear, Integer graduationYear, String cohortStatus,
                               String finalClassId, String finalClassCode, String graduationAcademicYearCode,
                               String graduationResult, String recordStatus, Instant graduatedAt) {}
}
