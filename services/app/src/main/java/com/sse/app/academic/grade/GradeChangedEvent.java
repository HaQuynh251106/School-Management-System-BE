package com.sse.app.academic.grade;

/**
 * Tín hiệu invalidate nhẹ. Listener chỉ phát sự kiện realtime sau khi
 * transaction điểm đã commit; client luôn tải lại dữ liệu từ API canonical.
 */
record GradeChangedEvent(String gradeId, String action) {}
