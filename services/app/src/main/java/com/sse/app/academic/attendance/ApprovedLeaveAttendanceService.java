package com.sse.app.academic.attendance;

import com.sse.app.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.ArrayList;

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
    private final ApplicationEventPublisher events;

    public ApprovedLeaveAttendanceService(AttendanceRepository records, NotificationService notifications,
                                          ApplicationEventPublisher events) {
        this.records = records;
        this.notifications = notifications;
        this.events = events;
    }

    @Transactional
    public int reconcile(String studentId, LocalDate startDate, LocalDate endDate) {
        int updated = 0;
        var changedIds = new ArrayList<String>();
        for (AttendanceRecord record : records.findByStudentIdAndDateBetween(studentId, startDate, endDate)) {
            if (record.getStatus() == null || !record.getStatus().startsWith("ABSENT")) continue;
            if (!"ABSENT_EXCUSED".equals(record.getStatus())
                    || !APPROVED_LEAVE_NOTE.equals(record.getNote())) {
                record.setStatus("ABSENT_EXCUSED");
                record.setNote(APPROVED_LEAVE_NOTE);
                records.save(record);
                changedIds.add(record.getId());
                updated++;
            }
            notifications.removeByReference("ATTENDANCE", record.getId());
        }
        if (!changedIds.isEmpty()) events.publishEvent(new AttendanceChangedEvent(changedIds));
        return updated;
    }
}
