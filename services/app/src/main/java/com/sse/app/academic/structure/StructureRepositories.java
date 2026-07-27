package com.sse.app.academic.structure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// Repository nội bộ phân hệ academic.structure (package-private — truy cập chéo domain qua StructureService).

interface AcademicYearRepository extends JpaRepository<AcademicYear, String> {
    Optional<AcademicYear> findByCode(String code);
    List<AcademicYear> findByStatus(String status);
}

interface SemesterRepository extends JpaRepository<Semester, String> {
    List<Semester> findByAcademicYearId(String academicYearId);
    List<Semester> findByStatus(String status);
    Optional<Semester> findByAcademicYearIdAndCode(String academicYearId, String code);
    Optional<Semester> findByAcademicYearIdAndSequence(String academicYearId, int sequence);
}

interface SchoolClassRepository extends JpaRepository<SchoolClass, String> {
    List<SchoolClass> findByAcademicYearId(String academicYearId);
    List<SchoolClass> findByGradeLevel(String gradeLevel);
    List<SchoolClass> findByHomeroomTeacherId(String homeroomTeacherId);
    List<SchoolClass> findByRoomId(String roomId);
    Optional<SchoolClass> findByAcademicYearIdAndCode(String academicYearId, String code);
    Optional<SchoolClass> findByAcademicYearIdAndStudyShiftAndRoomId(
            String academicYearId, String studyShift, String roomId);
}

interface SubjectRepository extends JpaRepository<Subject, String> {
    Optional<Subject> findByCode(String code);
}

interface RoomRepository extends JpaRepository<Room, String> {
    Optional<Room> findByCode(String code);
}

interface ClassEnrollmentRepository extends JpaRepository<ClassEnrollment, String> {
    List<ClassEnrollment> findByStudentIdAndStatus(String studentId, String status);
    Optional<ClassEnrollment> findByAcademicYearIdAndClassIdAndStudentId(
            String academicYearId, String classId, String studentId);
}
