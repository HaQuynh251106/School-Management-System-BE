package com.sse.app.academic.leave;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.time.LocalDate;

interface LeaveRequestRepository extends JpaRepository<LeaveRequest, String> {
    List<LeaveRequest> findByStudentId(String studentId);
    List<LeaveRequest> findByClassId(String classId);
    List<LeaveRequest> findByStudentIdAndEndDateGreaterThanEqualAndStartDateLessThanEqual(
            String studentId, LocalDate startDate, LocalDate endDate);
    List<LeaveRequest> findByClassIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            String classId, LocalDate endDate, LocalDate startDate);
}
