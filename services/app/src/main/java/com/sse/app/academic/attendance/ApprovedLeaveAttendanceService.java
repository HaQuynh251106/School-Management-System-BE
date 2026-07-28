package com.sse.app.academic.attendance;

import com.sse.app.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Đồng bộ sổ điểm danh khi một đơn xin nghỉ được GVCN phê duyệt.
 *
 * <p>Những lượt đã ghi nhận vắng trong khoảng nghỉ được chuyển thành vắng có phép.
 * Cảnh báo chuyên cần cũ được gỡ vì thông báo duyệt đơn đã là nguồn thông tin chính xác
 * cho học sinh và phụ huynh.</p>
 */
@Service
public class ApprovedLeaveAttendanceService {

    private static final String APPROVED_LEAVE_NOTE = "Đơn xin nghỉ đã được GVCN duyệt";

    private final AttendanceRepository records;
    private final NotificationService notifications;

    public ApprovedLeaveAttendanceService(AttendanceRepository records, NotificationService notifications) {
        this.records = records;
        this.notifications = notifications;
    }

    @Transactional
    public int reconcile(String studentId, LocalDate startDate, LocalDate endDate) {
        int updated = 0;
        for (AttendanceRecord record : records.findByStudentIdAndDateBetween(studentId, startDate, endDate)) {
            if (record.getStatus() == null || !record.getStatus().startsWith("ABSENT")) continue;
            if (!"ABSENT_EXCUSED".equals(record.getStatus())
                    || !APPROVED_LEAVE_NOTE.equals(record.getNote())) {
                record.setStatus("ABSENT_EXCUSED");
                record.setNote(APPROVED_LEAVE_NOTE);
                records.save(record);
                updated++;
            }
            notifications.removeByReference("ATTENDANCE", record.getId());
        }
        return updated;
    }
}
