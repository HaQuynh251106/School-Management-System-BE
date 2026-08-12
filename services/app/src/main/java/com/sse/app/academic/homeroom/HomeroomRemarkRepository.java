package com.sse.app.academic.homeroom;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface HomeroomRemarkRepository extends JpaRepository<HomeroomRemark, String> {
    Optional<HomeroomRemark> findByStudentIdAndSemesterId(String studentId, String semesterId);
    List<HomeroomRemark> findByStudentIdOrderByUpdatedAtDesc(String studentId);
}
