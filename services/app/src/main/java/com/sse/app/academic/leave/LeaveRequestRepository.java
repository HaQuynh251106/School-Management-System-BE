package com.sse.app.academic.leave;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface LeaveRequestRepository extends JpaRepository<LeaveRequest, String> {
    List<LeaveRequest> findByStudentId(String studentId);
    List<LeaveRequest> findByClassId(String classId);
}
