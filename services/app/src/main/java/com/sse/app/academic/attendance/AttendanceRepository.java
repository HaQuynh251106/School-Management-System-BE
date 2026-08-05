package com.sse.app.academic.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

interface AttendanceRepository extends JpaRepository<AttendanceRecord, String> {
    List<AttendanceRecord> findByStudentId(String studentId);
    List<AttendanceRecord> findByClassId(String classId);
    List<AttendanceRecord> findByClassIdAndDate(String classId, LocalDate date);
    List<AttendanceRecord> findBySlotIdAndDate(String slotId, LocalDate date);
    Optional<AttendanceRecord> findBySlotIdAndDateAndStudentId(String slotId, LocalDate date, String studentId);
    List<AttendanceRecord> findByStudentIdAndDateBetweenOrderByDateDesc(
            String studentId, LocalDate fromDate, LocalDate toDate);
}

interface AttendanceExcuseRequestRepository
        extends JpaRepository<AttendanceExcuseRequest, String> {
    List<AttendanceExcuseRequest> findByStudentIdOrderByRequestedAtDesc(String studentId);
    List<AttendanceExcuseRequest> findByStatusOrderByRequestedAtAsc(String status);
    Optional<AttendanceExcuseRequest> findByAttendanceRecordIdAndStatus(
            String attendanceRecordId, String status);
}
