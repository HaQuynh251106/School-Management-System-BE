package com.sse.app.operations;

import com.sse.app.common.SchedulerExecutionRegistry;
import com.sse.app.finance.VietQrGateway;
import com.sse.app.realtime.RealtimeEventHub;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.sse.app.operations.AdminOperationsDtos.*;

/** Read-only operational telemetry intended for the system administrator. */
@Service
public class AdminOperationsService {
    private final JdbcOperations jdbc;
    private final SchedulerExecutionRegistry scheduler;
    private final RealtimeEventHub realtime;
    private final VietQrGateway vietQr;
    private final boolean mailEnabled;
    private final Path backupPath;
    private final Path uploadPath;

    public AdminOperationsService(JdbcOperations jdbc, SchedulerExecutionRegistry scheduler,
                                  RealtimeEventHub realtime, VietQrGateway vietQr,
                                  @Value("${sse.mail.enabled:false}") boolean mailEnabled,
                                  @Value("${sse.backup.path:/data/backups}") String backupPath,
                                  @Value("${sse.storage.path:/data/uploads}") String uploadPath) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.realtime = realtime;
        this.vietQr = vietQr;
        this.mailEnabled = mailEnabled;
        this.backupPath = Path.of(backupPath).toAbsolutePath().normalize();
        this.uploadPath = Path.of(uploadPath).toAbsolutePath().normalize();
    }

    public Snapshot snapshot() {
        Instant now = Instant.now();
        List<SchedulerExecutionRegistry.JobState> jobs = scheduler.snapshot();
        DeliverySummary deliveries = deliverySummary();
        ImportSummary imports = importSummary();
        BackupSummary backup = backupSummary(now);
        StorageSummary storage = storageSummary();
        List<ComponentStatus> components = componentStatuses(now, jobs, backup);
        List<ActionItem> actions = actionItems(deliveries, backup, jobs);
        return new Snapshot(now, components, jobs, deliveries, imports, backup, storage, actions);
    }

    private List<ComponentStatus> componentStatuses(Instant now,
                                                    List<SchedulerExecutionRegistry.JobState> jobs,
                                                    BackupSummary backup) {
        List<ComponentStatus> result = new ArrayList<>();
        result.add(new ComponentStatus("backend", "Backend API", "UP", "Dịch vụ đang phản hồi", now));
        try {
            String version = jdbc.queryForObject("select version()", String.class);
            result.add(new ComponentStatus("postgresql", "PostgreSQL", "UP",
                    version == null ? "Kết nối thành công" : version.split(" on ")[0], now));
        } catch (Exception exception) {
            result.add(new ComponentStatus("postgresql", "PostgreSQL", "DOWN",
                    safeMessage(exception), now));
        }
        result.add(new ComponentStatus("smtp", "Email SMTP", mailEnabled ? "UP" : "WARNING",
                mailEnabled ? "Đã bật kênh gửi email" : "Chưa bật dịch vụ email", now));
        result.add(new ComponentStatus("sse", "Thông báo thời gian thực", "UP",
                realtime.connectedUserCount() + " người dùng · " + realtime.activeConnectionCount() + " kết nối", now));
        Map<String, Object> qr = vietQr.configurationStatus();
        boolean qrReady = Boolean.TRUE.equals(qr.get("configured"));
        result.add(new ComponentStatus("vietqr", "VietQR", qrReady ? "UP" : "WARNING",
                qrReady ? "Đã cấu hình ngân hàng " + qr.get("bankId") + " · tài khoản ••" + qr.get("accountSuffix")
                        : "Chưa đủ cấu hình nhận thanh toán", now));
        long failedJobs = jobs.stream().filter(job -> "FAILED".equals(job.status())).count();
        result.add(new ComponentStatus("scheduler", "Tác vụ tự động", failedJobs == 0 ? "UP" : "DOWN",
                jobs.isEmpty() ? "Đang chờ lần chạy đầu tiên" : failedJobs == 0
                        ? jobs.size() + " tác vụ đã được ghi nhận" : failedJobs + " tác vụ chạy lỗi", now));
        result.add(new ComponentStatus("backup", "Sao lưu dữ liệu", backup.status(), backup.detail(), now));
        return List.copyOf(result);
    }

    private DeliverySummary deliverySummary() {
        return jdbc.queryForObject("""
                select count(*) filter (where status='PENDING'),
                       count(*) filter (where status='RETRYING'),
                       count(*) filter (where status='FAILED'),
                       count(*) filter (where status='DELIVERED' and created_at >= current_date),
                       max(created_at) filter (where status='FAILED')
                from notification_delivery_logs
                """, (rs, row) -> new DeliverySummary(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                rs.getInt(4), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()));
    }

    private ImportSummary importSummary() {
        Integer count = jdbc.queryForObject(
                "select count(*) from audit_logs where action='ACCOUNT_IMPORT_COMPLETED'", Integer.class);
        List<Map<String, Object>> latest = jdbc.queryForList("""
                select created_at, detail from audit_logs
                where action='ACCOUNT_IMPORT_COMPLETED' order by created_at desc limit 1
                """);
        if (latest.isEmpty()) return new ImportSummary(count == null ? 0 : count, null, null);
        Object createdAt = latest.get(0).get("created_at");
        Instant instant = createdAt instanceof java.sql.Timestamp timestamp ? timestamp.toInstant()
                : createdAt instanceof java.time.OffsetDateTime offset ? offset.toInstant() : null;
        return new ImportSummary(count == null ? 0 : count, instant,
                (String) latest.get(0).get("detail"));
    }

    private BackupSummary backupSummary(Instant now) {
        if (!Files.isDirectory(backupPath)) {
            return new BackupSummary("WARNING", null, null, 0,
                    "Chưa gắn thư mục sao lưu vào Backend");
        }
        try (Stream<Path> files = Files.list(backupPath)) {
            Path latest = files.filter(Files::isRegularFile)
                    .max(Comparator.comparing(this::lastModified)).orElse(null);
            if (latest == null) return new BackupSummary("WARNING", null, null, 0,
                    "Chưa tìm thấy bản sao lưu");
            Instant modified = lastModified(latest);
            long size = Files.size(latest);
            boolean recent = Duration.between(modified, now).abs().toHours() <= 48;
            return new BackupSummary(recent ? "UP" : "WARNING", latest.getFileName().toString(),
                    modified, size, recent ? "Bản sao lưu gần nhất còn hiệu lực"
                    : "Bản sao lưu gần nhất đã quá 48 giờ");
        } catch (IOException exception) {
            return new BackupSummary("DOWN", null, null, 0, safeMessage(exception));
        }
    }

    private StorageSummary storageSummary() {
        try {
            Files.createDirectories(uploadPath);
            long uploadBytes;
            try (Stream<Path> files = Files.walk(uploadPath)) {
                uploadBytes = files.filter(Files::isRegularFile).mapToLong(this::size).sum();
            }
            return new StorageSummary(uploadPath.toFile().getUsableSpace(),
                    uploadPath.toFile().getTotalSpace(), uploadBytes);
        } catch (IOException exception) {
            return new StorageSummary(0, 0, 0);
        }
    }

    private List<ActionItem> actionItems(DeliverySummary deliveries, BackupSummary backup,
                                         List<SchedulerExecutionRegistry.JobState> jobs) {
        List<ActionItem> result = new ArrayList<>();
        add(result, deliveries.failed(), "delivery-failures", "CRITICAL", "Lần gửi thông báo thất bại",
                "Mở danh sách để kiểm tra nguyên nhân và gửi lại an toàn.", "A10");
        add(result, integer("select count(*) from users where coalesce(activation_status,'ACTIVE') <> 'ACTIVE'"),
                "pending-activation", "WARNING", "Tài khoản chờ kích hoạt",
                "Gửi lại liên kết kích hoạt cho người dùng có email hợp lệ.", "A1L");
        add(result, integer("select count(*) from users where password_change_required=true"),
                "password-change", "WARNING", "Tài khoản cần đổi mật khẩu",
                "Theo dõi tiến độ người dùng hoàn tất thiết lập bảo mật.", "A1L");
        if (!"UP".equals(backup.status())) result.add(new ActionItem("backup", "CRITICAL",
                "Sao lưu cần kiểm tra", backup.detail(), 1, "A10"));
        long failedJobs = jobs.stream().filter(job -> "FAILED".equals(job.status())).count();
        add(result, failedJobs, "scheduler-failures", "CRITICAL", "Tác vụ tự động chạy lỗi",
                "Kiểm tra chi tiết lần chạy và log Backend trước khi thử lại.", "A10");
        return List.copyOf(result);
    }

    private void add(List<ActionItem> items, long value, String key, String severity,
                     String title, String detail, String pageCode) {
        if (value > 0) items.add(new ActionItem(key, severity, title, detail, value, pageCode));
    }

    private int integer(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private Instant lastModified(Path path) {
        try { return Files.getLastModifiedTime(path).toInstant(); }
        catch (IOException exception) { return Instant.EPOCH; }
    }

    private long size(Path path) {
        try { return Files.size(path); }
        catch (IOException exception) { return 0; }
    }

    private String safeMessage(Exception exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) return "Không xác định được nguyên nhân";
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
}
