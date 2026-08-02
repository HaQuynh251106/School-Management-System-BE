package com.sse.app.academic.exam;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.sse.app.academic.grade.*;
import com.sse.app.academic.structure.*;
import com.sse.app.common.ApiException;
import com.sse.app.identity.*;
import com.sse.app.academic.summary.ReportCardDtos.ReportCardView;
import com.sse.app.academic.summary.ReportCardWorkflowService;
import com.sse.app.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class ExamReportService {
    private final ExamService exams;
    private final ExamScheduleRepository schedules;
    private final ExamCandidateRepository candidates;
    private final ExamResultRepository results;
    private final GradeService grades;
    private final GradeCalculationService gradeCalculations;
    private final StructureService structure;
    private final UserService users;
    private final ReportCardWorkflowService reportCards;

    public byte[] scoreSlip(String periodId, String studentId) {
        ExamPeriod period = exams.requirePeriod(periodId); User student = users.getById(studentId);
        List<ExamResult> studentResults = results.findByExamPeriodIdAndStudentId(periodId, studentId).stream()
                .filter(result -> "PUBLISHED".equals(result.getStatus())).toList();
        Map<String, ExamSchedule> scheduleMap = schedules.findByExamPeriodId(periodId).stream()
                .collect(Collectors.toMap(ExamSchedule::getId, s -> s));
        return pdf(false, document -> {
            title(document, "PHIẾU ĐIỂM KỲ THI", period.getName());
            info(document, List.of("Học sinh: " + student.getFullName(), "Mã học sinh: " + value(student.getStudentCode()),
                    "Lớp: " + value(student.getClassName()), "Kỳ thi: " + period.getCode()));
            PdfPTable table = table(new float[]{1, 5, 2, 3}, "STT", "Môn thi", "Điểm", "Trạng thái");
            int index = 1;
            for (ExamResult result : studentResults.stream().sorted(Comparator.comparing(r -> scheduleMap.get(r.getScheduleId()).getSubjectName())).toList()) {
                ExamSchedule schedule = scheduleMap.get(result.getScheduleId());
                row(table, String.valueOf(index++), schedule.getSubjectName(), score(result.getScore()), statusLabel(result.getStatus()));
            }
            document.add(table); signature(document, period);
        });
    }

    public byte[] reportCard(String academicYearId, String studentId, CurrentUser actor) {
        ReportCardView card = reportCards.view(academicYearId, studentId, actor);
        return pdf(false, document -> {
            title(document, "HỌC BẠ ĐIỆN TỬ", "Năm học " + card.academicYearCode());
            info(document, List.of("Học sinh: " + card.studentName(), "Mã học sinh: " + value(card.studentCode()),
                    "Lớp: " + value(card.classCode()), "Giáo viên chủ nhiệm: " + value(card.homeroomTeacherName())));
            PdfPTable table = table(new float[]{1, 5, 2, 2, 2, 3}, "STT", "Môn học", "HKI", "HKII", "Cả năm", "Xếp loại");
            int index = 1;
            for (var subject : card.subjects()) {
                row(table, String.valueOf(index++), subject.subjectName(), score(subject.semesterOneAverage()),
                        score(subject.semesterTwoAverage()), score(subject.annualAverage()), classify(subject.annualAverage()));
            }
            document.add(table);
            heading(document, "Tổng kết và rèn luyện");
            info(document, List.of("Trung bình HKI: " + score(card.semesterOneAverage()),
                    "Trung bình HKII: " + score(card.semesterTwoAverage()),
                    "Trung bình cả năm: " + score(card.annualAverage()),
                    "Hạnh kiểm: " + conductLabel(card.conductGrade()),
                    "Kết quả: " + promotionLabel(card.promotionStatus()),
                    "Nhận xét GVCN: " + value(card.homeroomComment()),
                    "Chuyên cần: Có mặt " + card.attendance().present() + " · Vắng có phép " + card.attendance().excusedAbsence()
                            + " · Vắng không phép " + card.attendance().unexcusedAbsence() + " · Đi muộn " + card.attendance().late()));
            Paragraph confirmation = new Paragraph("Mã xác nhận học bạ: " + card.verificationCode(), font(9, Font.ITALIC));
            confirmation.setSpacingBefore(14); document.add(confirmation); genericSignature(document);
        });
    }

    private String conductLabel(String value) {
        if (value == null) return "-";
        return switch (value) { case "GOOD" -> "Tốt"; case "FAIR" -> "Khá"; case "AVERAGE" -> "Trung bình"; case "WEAK" -> "Yếu"; default -> value; };
    }

    private String promotionLabel(String value) {
        if (value == null) return "Chưa xác định";
        return switch (value) { case "PROMOTED" -> "Được lên lớp"; case "GRADUATED" -> "Tốt nghiệp"; case "RETAINED" -> "Lưu ban"; case "READY" -> "Đủ điều kiện tổng kết"; default -> value; };
    }

    public byte[] exportPdf(String periodId, String scope, String classId, String gradeLevel) {
        ExamPeriod period = exams.requirePeriod(periodId); List<ExamSchedule> periodSchedules = schedules.findByExamPeriodId(periodId);
        List<ExamCandidate> selected = selectedCandidates(periodId, scope, classId, gradeLevel);
        Map<String, ExamCandidate> students = selected.stream().collect(Collectors.toMap(ExamCandidate::getStudentId, c -> c, (a, b) -> a, LinkedHashMap::new));
        Map<String, Map<String, ExamResult>> resultMap = results.findByExamPeriodId(periodId).stream()
                .collect(Collectors.groupingBy(ExamResult::getStudentId, Collectors.toMap(ExamResult::getSubjectId, r -> r, (a, b) -> a)));
        return pdf(true, document -> {
            title(document, "BẢNG ĐIỂM KỲ THI", period.getName() + " - " + scopeLabel(scope, classId, gradeLevel));
            float[] widths = new float[5 + periodSchedules.size()]; Arrays.fill(widths, 1.6f); widths[0] = .7f; widths[1] = 2.2f; widths[2] = 3.8f; widths[3] = 1.4f; widths[widths.length - 1] = 1.5f;
            List<String> headers = new ArrayList<>(List.of("STT", "SBD", "Họ và tên", "Lớp"));
            headers.addAll(periodSchedules.stream().map(ExamSchedule::getSubjectName).toList()); headers.add("TB");
            PdfPTable table = table(widths, headers.toArray(String[]::new)); int index = 1;
            for (ExamCandidate candidate : students.values()) {
                List<String> cells = new ArrayList<>(List.of(String.valueOf(index++), candidate.getCandidateNo(), candidate.getStudentName(), candidate.getClassCode()));
                double sum = 0; int count = 0;
                for (ExamSchedule schedule : periodSchedules) { Double value = Optional.ofNullable(resultMap.get(candidate.getStudentId())).map(m -> m.get(schedule.getSubjectId())).map(ExamResult::getScore).orElse(null); cells.add(score(value)); if (value != null) { sum += value; count++; } }
                cells.add(count == 0 ? "-" : score(Math.round(sum / count * 10d) / 10d)); row(table, cells.toArray(String[]::new));
            }
            document.add(table); signature(document, period);
        });
    }

    public byte[] exportCsv(String periodId, String scope, String classId, String gradeLevel) {
        List<ExamSchedule> periodSchedules = schedules.findByExamPeriodId(periodId);
        Map<String, ExamCandidate> students = selectedCandidates(periodId, scope, classId, gradeLevel).stream()
                .collect(Collectors.toMap(ExamCandidate::getStudentId, c -> c, (a, b) -> a, LinkedHashMap::new));
        Map<String, Map<String, ExamResult>> resultMap = results.findByExamPeriodId(periodId).stream()
                .collect(Collectors.groupingBy(ExamResult::getStudentId, Collectors.toMap(ExamResult::getSubjectId, r -> r, (a, b) -> a)));
        StringBuilder out = new StringBuilder("\uFEFFSBD,Mã học sinh,Họ và tên,Lớp");
        periodSchedules.forEach(s -> out.append(',').append(csv(s.getSubjectName()))); out.append(",Trung bình\r\n");
        students.values().forEach(candidate -> {
            out.append(csv(candidate.getCandidateNo())).append(',').append(csv(candidate.getStudentCode())).append(',')
                    .append(csv(candidate.getStudentName())).append(',').append(csv(candidate.getClassCode()));
            double sum = 0; int count = 0;
            for (ExamSchedule schedule : periodSchedules) { Double value = Optional.ofNullable(resultMap.get(candidate.getStudentId())).map(m -> m.get(schedule.getSubjectId())).map(ExamResult::getScore).orElse(null); out.append(',').append(value == null ? "" : score(value)); if (value != null) { sum += value; count++; } }
            out.append(',').append(count == 0 ? "" : score(Math.round(sum / count * 10d) / 10d)).append("\r\n");
        });
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private List<ExamCandidate> selectedCandidates(String periodId, String scope, String classId, String gradeLevel) {
        exams.requirePeriod(periodId);
        if (!Set.of("CLASS", "GRADE", "YEAR").contains(scope)) throw ApiException.badRequest("Phạm vi xuất báo cáo không hợp lệ");
        if ("CLASS".equals(scope) && (classId == null || classId.isBlank())) throw ApiException.badRequest("Cần chọn lớp để xuất báo cáo");
        if ("GRADE".equals(scope) && (gradeLevel == null || gradeLevel.isBlank())) throw ApiException.badRequest("Cần chọn khối để xuất báo cáo");
        return candidates.findByExamPeriodId(periodId).stream()
                .filter(c -> !"CLASS".equals(scope) || Objects.equals(classId, c.getClassId()))
                .filter(c -> !"GRADE".equals(scope) || Objects.equals(gradeLevel, structure.getClass(c.getClassId()).getGradeLevel()))
                .sorted(Comparator.comparing(ExamCandidate::getClassCode).thenComparing(ExamCandidate::getCandidateNo)).toList();
    }

    private byte[] pdf(boolean landscape, PdfBody body) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(landscape ? PageSize.A4.rotate() : PageSize.A4, 32, 32, 32, 34);
            PdfWriter writer = PdfWriter.getInstance(document, out); writer.setPageEvent(new PageNumbers());
            document.open(); body.write(document); document.close(); return out.toByteArray();
        } catch (Exception e) { throw new IllegalStateException("Không thể tạo tệp PDF", e); }
    }
    private void title(Document d, String title, String subtitle) throws DocumentException {
        Paragraph school = new Paragraph("TRƯỜNG HỌC SỐ", font(10, Font.BOLD)); school.setAlignment(Element.ALIGN_CENTER); d.add(school);
        Paragraph heading = new Paragraph(title, font(17, Font.BOLD)); heading.setAlignment(Element.ALIGN_CENTER); heading.setSpacingBefore(5); d.add(heading);
        Paragraph sub = new Paragraph(subtitle, font(10, Font.NORMAL)); sub.setAlignment(Element.ALIGN_CENTER); sub.setSpacingAfter(15); d.add(sub);
    }
    private void info(Document d, List<String> lines) throws DocumentException { for (String line : lines) d.add(new Paragraph(line, font(10, Font.NORMAL))); d.add(Chunk.NEWLINE); }
    private void heading(Document d, String value) throws DocumentException { Paragraph p = new Paragraph(value, font(12, Font.BOLD)); p.setSpacingBefore(10); p.setSpacingAfter(6); d.add(p); }
    private PdfPTable table(float[] widths, String... headers) throws DocumentException {
        PdfPTable table = new PdfPTable(widths); table.setWidthPercentage(100); table.setSpacingAfter(12); table.setHeaderRows(1);
        for (String header : headers) { PdfPCell cell = cell(header, true); cell.setHorizontalAlignment(Element.ALIGN_CENTER); table.addCell(cell); } return table;
    }
    private void row(PdfPTable table, String... values) { for (String value : values) table.addCell(cell(value, false)); }
    private PdfPCell cell(String value, boolean header) { PdfPCell cell = new PdfPCell(new Phrase(value(value), font(header ? 8 : 8, header ? Font.BOLD : Font.NORMAL))); cell.setPadding(5); cell.setVerticalAlignment(Element.ALIGN_MIDDLE); if (header) cell.setBackgroundColor(new Color(225, 237, 250)); return cell; }
    private void signature(Document d, ExamPeriod p) throws DocumentException { Paragraph confirm = new Paragraph("Trạng thái xác nhận: " + (p.getConfirmedAt() == null ? "Chưa xác nhận" : "Đã xác nhận lúc " + p.getConfirmedAt()) + " | Mã: " + p.getId(), font(8, Font.ITALIC)); confirm.setSpacingBefore(8); d.add(confirm); genericSignature(d); }
    private void genericSignature(Document d) throws DocumentException { PdfPTable sign = new PdfPTable(2); sign.setWidthPercentage(70); sign.setHorizontalAlignment(Element.ALIGN_RIGHT); sign.setSpacingBefore(18); PdfPCell a = new PdfPCell(new Phrase("NGƯỜI LẬP BIỂU\n(Ký, ghi rõ họ tên)", font(9, Font.BOLD))); PdfPCell b = new PdfPCell(new Phrase("HIỆU TRƯỞNG\n(Ký, đóng dấu)", font(9, Font.BOLD))); for (PdfPCell c : List.of(a, b)) { c.setBorder(Rectangle.NO_BORDER); c.setHorizontalAlignment(Element.ALIGN_CENTER); c.setMinimumHeight(65); sign.addCell(c); } d.add(sign); }
    private Font font(float size, int style) { return new Font(pdfBaseFont(), size, style, Color.BLACK); }
    private BaseFont pdfBaseFont() {
        try {
            String configured = System.getenv("SSE_PDF_FONT_PATH");
            List<String> paths = new ArrayList<>(); if (configured != null) paths.add(configured);
            paths.add("C:/Windows/Fonts/arial.ttf"); paths.add("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
            for (String path : paths) if (new File(path).isFile()) return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) { throw new IllegalStateException("Không thể nạp phông chữ PDF", e); }
    }
    private String classify(Double value) { if (value == null) return "Chưa đủ điểm"; if (value >= 8) return "Tốt"; if (value >= 6.5) return "Khá"; if (value >= 5) return "Đạt"; return "Cần cố gắng"; }
    private String statusLabel(String value) { return switch (value) { case "DRAFT" -> "Bản nháp"; case "PUBLISHED" -> "Đã công bố"; default -> value(value); }; }
    private String score(Double value) { return value == null ? "-" : String.format(Locale.US, "%.1f", value); }
    private String value(Object value) { return value == null || value.toString().isBlank() ? "-" : value.toString(); }
    private String csv(String value) { return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\""; }
    private String scopeLabel(String scope, String classId, String gradeLevel) { return switch (scope) { case "CLASS" -> "Lớp " + structure.getClass(classId).getCode(); case "GRADE" -> "Khối " + value(gradeLevel); default -> "Toàn kỳ thi"; }; }
    @FunctionalInterface private interface PdfBody { void write(Document document) throws Exception; }
    private static class PageNumbers extends PdfPageEventHelper { public void onEndPage(PdfWriter writer, Document document) { ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_CENTER, new Phrase("Trang " + writer.getPageNumber(), new Font(Font.HELVETICA, 8)), (document.right() + document.left()) / 2, document.bottom() - 12, 0); } }
}
