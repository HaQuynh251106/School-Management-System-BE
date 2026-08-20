package com.sse.app.academic.structure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// Repository nội bộ phân hệ academic.structure (package-private — truy cập chéo domain qua StructureService).

interface AcademicYearRepository extends JpaRepository<AcademicYear, String> {
    Optional<AcademicYear> findByCodeIgnoreCase(String code);
}

interface GradeLevelRepository extends JpaRepository<GradeLevel, String> {
    List<GradeLevel> findByActiveTrueOrderByDisplayOrder();
}

interface SemesterRepository extends JpaRepository<Semester, String> {
    List<Semester> findByAcademicYearId(String academicYearId);
    Optional<Semester> findByAcademicYearIdAndCodeIgnoreCase(String academicYearId, String code);
}

interface SchoolClassRepository extends JpaRepository<SchoolClass, String> {
    List<SchoolClass> findByAcademicYearId(String academicYearId);
    List<SchoolClass> findByGradeLevel(String gradeLevel);
    List<SchoolClass> findByHomeroomTeacherId(String homeroomTeacherId);
    Optional<SchoolClass> findByAcademicYearIdAndCode(String academicYearId, String code);
    List<SchoolClass> findByAcademicYearIdAndHomeroomTeacherId(
            String academicYearId, String homeroomTeacherId);
    Optional<SchoolClass> findByHomeRoomId(String homeRoomId);
    long countByAcademicYearIdAndGradeLevel(String academicYearId, String gradeLevel);
}

interface SubjectRepository extends JpaRepository<Subject, String> {
    Optional<Subject> findByCodeIgnoreCase(String code);
}

interface RoomRepository extends JpaRepository<Room, String> {
    Optional<Room> findByCodeIgnoreCase(String code);
}

interface SchoolHolidayRepository extends JpaRepository<SchoolHoliday, String> {
    List<SchoolHoliday> findByAcademicYearIdOrderByDate(String academicYearId);
}
