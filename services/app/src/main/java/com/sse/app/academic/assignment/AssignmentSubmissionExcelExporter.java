package com.sse.app.academic.assignment;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Component
class AssignmentSubmissionExcelExporter {
    byte[] export(Assignment assignment, List<AssignmentSubmission> submissions) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Bai nop");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("Bai tap");
            title.createCell(1).setCellValue(assignment.getTitle());
            Row header = sheet.createRow(2);
            String[] labels = {"Ma hoc sinh", "Ho ten", "Trang thai",
                    "Lan nop", "Thoi gian nop", "Ten file", "Diem", "Nhan xet"};
            for (int i = 0; i < labels.length; i++) {
                header.createCell(i).setCellValue(labels[i]);
            }
            int index = 3;
            for (AssignmentSubmission submission : submissions) {
                Row row = sheet.createRow(index++);
                row.createCell(0).setCellValue(submission.getStudentId());
                row.createCell(1).setCellValue(value(submission.getStudentName()));
                row.createCell(2).setCellValue(value(submission.getStatus()));
                row.createCell(3).setCellValue(submission.getCurrentVersion() == null
                        ? 1 : submission.getCurrentVersion());
                row.createCell(4).setCellValue(submission.getSubmittedAt() == null
                        ? "" : submission.getSubmittedAt().toString());
                row.createCell(5).setCellValue(value(submission.getAttachmentName()));
                if (submission.getScore() != null) {
                    row.createCell(6).setCellValue(submission.getScore());
                }
                row.createCell(7).setCellValue(value(submission.getFeedback()));
            }
            for (int i = 0; i < labels.length; i++) sheet.autoSizeColumn(i);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Khong the xuat so cham bai", e);
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
