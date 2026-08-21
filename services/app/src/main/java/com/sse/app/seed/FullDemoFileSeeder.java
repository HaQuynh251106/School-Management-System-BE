package com.sse.app.seed;

import com.sse.app.file.MinioStorageProperties;
import com.sse.app.file.StoredFile;
import com.sse.app.file.StoredFileRepository;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Uploads deterministic demo objects only when the reset script confirms MinIO is available. */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "sse.seed.files", havingValue = "true")
public class FullDemoFileSeeder {
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Bean
    @Order(30)
    ApplicationRunner fullDemoFiles(
            @Value("${sse.seed.dataset:baseline}") String dataset,
            MinioClient minio,
            MinioStorageProperties properties,
            StoredFileRepository files,
            JdbcTemplate jdbc) {
        return args -> {
            if (!"full-demo".equalsIgnoreCase(dataset.trim())) return;
            ensureBucket(minio, properties.getBucket());
            String assignmentKey = "full-demo/assignments/de-bai-toan-hk1.pdf";
            byte[] assignmentBytes = demoPdf("De bai Toan HK1 - Full Demo");
            put(minio, properties.getBucket(), assignmentKey, "application/pdf", assignmentBytes);
            save(files, "fd-file-assignment", assignmentKey, "ASSIGNMENT",
                    "de-bai-toan-hk1.pdf", "application/pdf", assignmentBytes.length,
                    "fd-teacher-001");
            jdbc.update("""
                    UPDATE assignments
                    SET attachment_file_id = 'fd-file-assignment',
                        attachment_file_key = ?, attachment_name = 'de-bai-toan-hk1.pdf',
                        attachment_content_type = 'application/pdf', attachment_size_bytes = ?
                    WHERE id = 'fd-assignment-published'
                    """, assignmentKey, demoPdf("De bai Toan HK1 - Full Demo").length);

            for (int index = 1; index <= 3; index++) {
                String suffix = String.format("%03d", index);
                String fileId = "fd-file-submission-" + suffix;
                String key = "full-demo/submissions/bai-lam-" + suffix + ".pdf";
                byte[] content = demoPdf("Bai lam demo " + suffix);
                put(minio, properties.getBucket(), key, "application/pdf", content);
                save(files, fileId, key, "SUBMISSION", "bai-lam-" + suffix + ".pdf",
                        "application/pdf", content.length, "fd-student-" + suffix);
                jdbc.update("""
                        UPDATE assignment_submissions
                        SET attachment_file_id = ?, attachment_file_key = ?,
                            attachment_name = ?, attachment_content_type = 'application/pdf',
                            attachment_size_bytes = ?
                        WHERE id = ?
                        """, fileId, key, "bai-lam-" + suffix + ".pdf", content.length,
                        "fd-submission-" + suffix);
                jdbc.update("""
                        UPDATE assignment_submission_versions
                        SET attachment_file_id = ?, attachment_name = ?,
                            attachment_content_type = 'application/pdf', attachment_size_bytes = ?
                        WHERE submission_id = ?
                        """, fileId, "bai-lam-" + suffix + ".pdf", content.length,
                        "fd-submission-" + suffix);
            }

            String proofKey = "full-demo/payment-proofs/yeu-cau-nop-lai.png";
            put(minio, properties.getBucket(), proofKey, "image/png", ONE_PIXEL_PNG);
            save(files, "fd-file-payment-proof", proofKey, "PAYMENT_PROOF",
                    "bien-lai-can-nop-lai.png", "image/png", ONE_PIXEL_PNG.length,
                    "fd-parent-001");
            jdbc.update("""
                    INSERT INTO payment_proofs (
                        id, payment_id, invoice_id, invoice_code, parent_id,
                        student_id, student_code, student_name, amount, file_id,
                        file_name, content_type, size_bytes, status, submitted_by,
                        submitted_at, reviewed_by, reviewed_at, review_reason,
                        transferred_at, bank_transaction_code
                    ) VALUES (
                        'fd-payment-proof-retry', 'fd-payment-pending', 'fd-invoice-001',
                        'HD-DEMO-001', 'fd-parent-001', 'fd-student-001', 'HS270001',
                        'Học sinh Demo 001', 1200000, 'fd-file-payment-proof',
                        'bien-lai-can-nop-lai.png', 'image/png', ?, 'RETRY_REQUIRED',
                        'fd-parent-001', now() - interval '2 days', 'fd-admin-001',
                        now() - interval '1 day', 'Ảnh biên lai chưa hiển thị rõ giao dịch',
                        now() - interval '2 days', 'MB-DEMO-RETRY-001'
                    )
                    ON CONFLICT (id) DO UPDATE SET
                        file_id = excluded.file_id, status = excluded.status,
                        review_reason = excluded.review_reason, reviewed_at = excluded.reviewed_at
                    """, ONE_PIXEL_PNG.length);

            seedReceipt(minio, properties.getBucket(), files, jdbc,
                    "partial", "PT-DEMO-002", "fd-admin-001");
            seedReceipt(minio, properties.getBucket(), files, jdbc,
                    "success", "PT-DEMO-003", "fd-admin-001");
            log.info("[seed] Full-demo assignment, submission, payment-proof and receipt files uploaded to MinIO.");
        };
    }

    private void seedReceipt(MinioClient minio, String bucket,
                             StoredFileRepository files, JdbcTemplate jdbc,
                             String suffix, String receiptNumber, String issuedBy)
            throws Exception {
        String fileId = "fd-file-receipt-" + suffix;
        String key = "receipts/" + receiptNumber + ".pdf";
        byte[] content = demoPdf("Bien nhan " + receiptNumber + " - Full Demo");
        put(minio, bucket, key, "application/pdf", content);
        save(files, fileId, key, "PAYMENT_RECEIPT", receiptNumber + ".pdf",
                "application/pdf", content.length, issuedBy);
        jdbc.update("""
                UPDATE payment_receipts
                SET file_id = ?, status = 'ISSUED', generated_at = now(),
                    generation_attempts = 1, generation_error = null
                WHERE id = ?
                """, fileId, "fd-receipt-" + suffix);
    }

    private void ensureBucket(MinioClient minio, String bucket) throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private void put(MinioClient minio, String bucket, String key, String contentType, byte[] bytes)
            throws Exception {
        minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .contentType(contentType)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
    }

    private void save(StoredFileRepository files, String id, String key, String scope,
                      String name, String contentType, long size, String ownerId) {
        Instant now = Instant.now();
        files.save(StoredFile.builder().id(id).fileKey(key).scope(scope)
                .originalName(name).contentType(contentType).sizeBytes(size)
                .uploadedBy(ownerId).status("READY").createdAt(now).completedAt(now).build());
    }

    private byte[] demoPdf(String title) {
        String escaped = title.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        String body = "BT /F1 16 Tf 72 720 Td (" + escaped + ") Tj ET";
        String pdf = "%PDF-1.4\n1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n"
                + "2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n"
                + "3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                + "/Resources<< /Font<< /F1 4 0 R >> >> /Contents 5 0 R >>endobj\n"
                + "4 0 obj<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>endobj\n"
                + "5 0 obj<< /Length " + body.length() + " >>stream\n" + body
                + "\nendstream endobj\ntrailer<< /Root 1 0 R >>\n%%EOF\n";
        return pdf.getBytes(StandardCharsets.US_ASCII);
    }
}
