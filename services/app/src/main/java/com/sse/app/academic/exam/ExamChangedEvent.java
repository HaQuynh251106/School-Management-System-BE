package com.sse.app.academic.exam;

/**
 * Tín hiệu invalidate của miền khảo thí. Sự kiện chỉ được đẩy tới client sau
 * khi transaction nghiệp vụ đã commit thành công.
 */
record ExamChangedEvent(String examPeriodId, String resultId, String reviewId, String action) {}
