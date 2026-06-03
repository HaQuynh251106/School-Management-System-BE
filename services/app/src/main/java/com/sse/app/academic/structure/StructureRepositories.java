package com.sse.app.academic.structure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// Repository nội bộ phân hệ academic.structure (package-private — truy cập chéo domain qua StructureService).

interface AcademicYearRepository extends JpaRepository<AcademicYear, String> {
}

interface SemesterRepository extends JpaRepository<Semester, String> {
    List<Semester> findByAcademicYearId(String academicYearId);
}

interface SchoolClassRepository extends JpaRepository<SchoolClass, String> {
    List<SchoolClass> findByAcademicYearId(String academicYearId);
    List<SchoolClass> findByGradeLevel(String gradeLevel);
    List<SchoolClass> findByHomeroomTeacherId(String homeroomTeacherId);
}

interface SubjectRepository extends JpaRepository<Subject, String> {
}

interface RoomRepository extends JpaRepository<Room, String> {
}

interface SchoolHolidayRepository extends JpaRepository<SchoolHoliday, String> {
}
