package com.sse.app.academic.assignment;

/**
 * Tín hiệu invalidate cho bài tập/bài nộp. Listener chỉ phát SSE sau khi
 * transaction đã commit để client tải lại đúng dữ liệu canonical từ API.
 */
record AssignmentChangedEvent(String assignmentId, String submissionId, String action) {}
