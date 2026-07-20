package com.sse.app.academic.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

interface AttendanceSessionAccessRepository extends JpaRepository<AttendanceSessionAccess, String> {
    Optional<AttendanceSessionAccess> findBySlotIdAndSessionDate(String slotId, LocalDate sessionDate);
}
