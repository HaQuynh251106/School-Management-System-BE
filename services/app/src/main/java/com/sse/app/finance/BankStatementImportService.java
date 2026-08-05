package com.sse.app.finance;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.finance.FinanceDtos.BankStatementEntryResponse;
import com.sse.app.finance.FinanceDtos.BankStatementImportResponse;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class BankStatementImportService {
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final BankStatementEntryRepository entries;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;

    public BankStatementImportService(
            BankStatementEntryRepository entries,
            InvoiceRepository invoices,
            PaymentRepository payments) {
        this.entries = entries;
        this.invoices = invoices;
        this.payments = payments;
    }

    @Transactional
    public BankStatementImportResponse importFile(
            MultipartFile file, String importedBy) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Bắt buộc chọn file sao kê");
        }
        String batchId = Ids.gen("bsi");
        List<StatementRow> rows = read(file);
        if (rows.isEmpty()) {
            throw ApiException.badRequest("File sao kê không có giao dịch");
        }
        List<Invoice> allInvoices = invoices.findAll();
        int matched = 0;
        int unmatched = 0;
        int mismatched = 0;
        int duplicates = 0;
        List<BankStatementEntryResponse> result = new ArrayList<>();
        for (StatementRow source : rows) {
            BankStatementEntry duplicate = entries
                    .findByBankCodeAndTransactionReference(
                            "MB", source.reference()).orElse(null);
            if (duplicate != null) {
                duplicates++;
                result.add(toResponse(duplicate));
                continue;
            }
            Invoice invoice = allInvoices.stream()
                    .filter(candidate -> candidate.getCode() != null
                            && source.content().toUpperCase(Locale.ROOT)
                            .contains(candidate.getCode().toUpperCase(Locale.ROOT)))
                    .findFirst().orElse(null);
            Payment payment = invoice == null ? null
                    : payments.findByInvoiceId(invoice.getId()).stream()
                    .filter(candidate -> candidate.getAmount() == source.amount())
                    .filter(candidate -> !"SUCCESS".equals(candidate.getStatus()))
                    .findFirst().orElse(null);
            String status;
            String mismatchReason = null;
            if (invoice == null) {
                status = "UNMATCHED";
                unmatched++;
            } else if (payment == null) {
                status = "MISMATCH";
                mismatchReason = "Không tìm thấy payment chờ xử lý có cùng số tiền";
                mismatched++;
            } else {
                status = "MATCHED";
                matched++;
            }
            BankStatementEntry saved = entries.save(BankStatementEntry.builder()
                    .id(Ids.gen("bse"))
                    .bankCode("MB")
                    .transactionReference(source.reference())
                    .amount(source.amount())
                    .transferredAt(source.transferredAt())
                    .transferContent(source.content())
                    .status(status)
                    .matchedInvoiceId(invoice == null ? null : invoice.getId())
                    .matchedPaymentId(payment == null ? null : payment.getId())
                    .mismatchReason(mismatchReason)
                    .importBatchId(batchId)
                    .importedBy(importedBy)
                    .importedAt(Instant.now())
                    .build());
            result.add(toResponse(saved));
        }
        return new BankStatementImportResponse(
                batchId, rows.size(), matched, unmatched,
                mismatched, duplicates, result);
    }

    public List<BankStatementEntryResponse> list(String status) {
        List<BankStatementEntry> rows =
                status == null || status.isBlank()
                        ? entries.findAll()
                        : entries.findByStatusOrderByTransferredAtDesc(
                        status.trim().toUpperCase(Locale.ROOT));
        return rows.stream().map(this::toResponse).toList();
    }

    private List<StatementRow> read(MultipartFile file) {
        String name = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            return name.endsWith(".xlsx") ? readExcel(file) : readCsv(file);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ApiException.badRequest(
                    "Không đọc được file sao kê: " + ex.getMessage());
        }
    }

    private List<StatementRow> readExcel(MultipartFile file) throws Exception {
        try (var workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getPhysicalNumberOfRows() < 2) return List.of();
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> columns = columns(sheet.getRow(0), formatter);
            List<StatementRow> rows = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) continue;
                String reference = cell(row, columns, formatter,
                        "transactionreference", "reference", "magiaodich");
                if (reference.isBlank()) continue;
                rows.add(new StatementRow(reference,
                        amount(cell(row, columns, formatter,
                                "amount", "sotien")),
                        transferredAt(cell(row, columns, formatter,
                                "transferredat", "time", "thoigian")),
                        cell(row, columns, formatter,
                                "content", "description", "noidung")));
            }
            return rows;
        }
    }

    private List<StatementRow> readCsv(MultipartFile file) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                file.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return List.of();
            List<String> names = csv(header);
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                columns.put(normalizeColumn(names.get(i)), i);
            }
            List<StatementRow> result = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> values = csv(line);
                String reference = value(values, columns,
                        "transactionreference", "reference", "magiaodich");
                if (reference.isBlank()) continue;
                result.add(new StatementRow(reference,
                        amount(value(values, columns, "amount", "sotien")),
                        transferredAt(value(values, columns,
                                "transferredat", "time", "thoigian")),
                        value(values, columns,
                                "content", "description", "noidung")));
            }
            return result;
        }
    }

    private Map<String, Integer> columns(Row row, DataFormatter formatter) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            result.put(normalizeColumn(formatter.formatCellValue(row.getCell(i))), i);
        }
        return result;
    }

    private String cell(Row row, Map<String, Integer> columns,
                        DataFormatter formatter, String... names) {
        for (String name : names) {
            Integer index = columns.get(name);
            if (index != null) return formatter.formatCellValue(row.getCell(index)).trim();
        }
        return "";
    }

    private String value(List<String> values, Map<String, Integer> columns,
                         String... names) {
        for (String name : names) {
            Integer index = columns.get(name);
            if (index != null && index < values.size()) return values.get(index).trim();
        }
        return "";
    }

    private List<String> csv(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private long amount(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9-]", "");
        if (digits.isBlank()) throw ApiException.badRequest("Sao kê thiếu số tiền");
        try {
            long value = Long.parseLong(digits);
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest("Số tiền sao kê không hợp lệ: " + raw);
        }
    }

    private Instant transferredAt(String raw) {
        if (raw == null || raw.isBlank()) return Instant.now();
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException ignored) {
            for (String pattern : List.of(
                    "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm",
                    "yyyy-MM-dd HH:mm:ss")) {
                try {
                    return LocalDateTime.parse(raw.trim(),
                                    DateTimeFormatter.ofPattern(pattern))
                            .atZone(SCHOOL_ZONE).toInstant();
                } catch (DateTimeParseException ignoredAgain) {
                    // Try the next supported MB statement format.
                }
            }
        }
        throw ApiException.badRequest("Thời gian giao dịch không hợp lệ: " + raw);
    }

    private String normalizeColumn(String value) {
        return java.text.Normalizer.normalize(
                        value == null ? "" : value,
                        java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
    }

    private BankStatementEntryResponse toResponse(BankStatementEntry row) {
        return new BankStatementEntryResponse(
                row.getId(), row.getTransactionReference(), row.getAmount(),
                row.getTransferredAt(), row.getTransferContent(), row.getStatus(),
                row.getMatchedInvoiceId(), row.getMatchedPaymentId(),
                row.getMismatchReason());
    }

    private record StatementRow(
            String reference, long amount,
            Instant transferredAt, String content) {}
}
