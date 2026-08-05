package com.sse.app.academic.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface AssignmentRepository extends JpaRepository<Assignment, String> {
    List<Assignment> findByClassId(String classId);
    List<Assignment> findByTeacherId(String teacherId);
}

interface AssignmentSubmissionRepository extends JpaRepository<AssignmentSubmission, String> {
    List<AssignmentSubmission> findByAssignmentId(String assignmentId);
    List<AssignmentSubmission> findByStudentId(String studentId);
    Optional<AssignmentSubmission> findByAssignmentIdAndStudentId(String assignmentId, String studentId);
}

interface AssignmentSubmissionVersionRepository
        extends JpaRepository<AssignmentSubmissionVersion, String> {
    List<AssignmentSubmissionVersion> findBySubmissionIdOrderByVersionNoDesc(String submissionId);
}

interface SubmissionResubmissionRequestRepository
        extends JpaRepository<SubmissionResubmissionRequest, String> {
    List<SubmissionResubmissionRequest> findBySubmissionIdOrderByRequestedAtDesc(String submissionId);
    Optional<SubmissionResubmissionRequest> findFirstBySubmissionIdAndStatusOrderByRequestedAtDesc(
            String submissionId, String status);
}
