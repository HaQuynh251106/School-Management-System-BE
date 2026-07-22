package com.sse.app.academic.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface AssignmentRepository extends JpaRepository<Assignment, String> {
    List<Assignment> findByClassId(String classId);
    List<Assignment> findByTeacherId(String teacherId);
    Optional<Assignment> findByAttachmentFileId(String attachmentFileId);
}

interface AssignmentSubmissionRepository extends JpaRepository<AssignmentSubmission, String> {
    List<AssignmentSubmission> findByAssignmentId(String assignmentId);
    List<AssignmentSubmission> findByStudentId(String studentId);
    Optional<AssignmentSubmission> findByAssignmentIdAndStudentId(String assignmentId, String studentId);
    Optional<AssignmentSubmission> findByAttachmentFileId(String attachmentFileId);
    long countByAssignmentId(String assignmentId);
}

interface AssignmentSubmissionAttemptRepository extends JpaRepository<AssignmentSubmissionAttempt, String> {
    List<AssignmentSubmissionAttempt> findBySubmissionIdOrderByAttemptNumberDesc(String submissionId);
    Optional<AssignmentSubmissionAttempt> findBySubmissionIdAndAttemptNumber(String submissionId, int attemptNumber);
    Optional<AssignmentSubmissionAttempt> findByAttachmentFileId(String attachmentFileId);
}
