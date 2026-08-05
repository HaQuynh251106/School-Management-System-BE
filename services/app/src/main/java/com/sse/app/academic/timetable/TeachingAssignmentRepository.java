package com.sse.app.academic.timetable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

interface TeachingAssignmentRepository extends JpaRepository<TeachingAssignment, String> {
    Optional<TeachingAssignment> findByClassIdAndSubjectIdAndSemesterId(
            String classId, String subjectId, String semesterId);

    Optional<TeachingAssignment> findByClassIdAndSubjectIdAndTeacherIdAndSemesterId(
            String classId, String subjectId, String teacherId, String semesterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select assignment from TeachingAssignment assignment "
            + "where assignment.classId = :classId and assignment.subjectId = :subjectId "
            + "and assignment.teacherId = :teacherId and assignment.semesterId = :semesterId")
    Optional<TeachingAssignment> lockForScheduling(
            @Param("classId") String classId,
            @Param("subjectId") String subjectId,
            @Param("teacherId") String teacherId,
            @Param("semesterId") String semesterId);

    List<TeachingAssignment> findByTeacherId(String teacherId);
    List<TeachingAssignment> findBySemesterId(String semesterId);
    void deleteBySemesterId(String semesterId);
}
