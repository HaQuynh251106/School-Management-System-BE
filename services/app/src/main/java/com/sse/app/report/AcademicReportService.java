package com.sse.app.report;

import com.sse.app.academic.assignment.Assignment;
import com.sse.app.academic.assignment.AssignmentService;
import com.sse.app.academic.assignment.AssignmentSubmission;
import com.sse.app.academic.attendance.AttendanceRecord;
import com.sse.app.academic.attendance.AttendanceService;
import com.sse.app.academic.grade.Grade;
import com.sse.app.academic.grade.GradeService;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.report.AcademicReportDtos.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AcademicReportService {
    private final GradeService grades;
    private final AttendanceService attendance;
    private final AssignmentService assignments;
    private final StructureService structure;
    private final UserService users;

    public AcademicReportService(GradeService grades, AttendanceService attendance,
                                 AssignmentService assignments, StructureService structure,
                                 UserService users) {
        this.grades = grades;
        this.attendance = attendance;
        this.assignments = assignments;
        this.structure = structure;
        this.users = users;
    }

    public AcademicReportResponse report(AcademicReportFilter filter) {
        Set<String> classIds = structure.listClasses(filter.academicYearId(), filter.gradeLevel()).stream()
                .map(SchoolClass::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (filter.classId() != null && !filter.classId().isBlank()) {
            classIds.retainAll(Set.of(filter.classId()));
        }
        Map<String, SchoolClass> classById = structure.listClasses(null, null).stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity(), (left, right) -> left));
        List<UserDto> studentRows = users.list("STUDENT", null, null).stream()
                .filter(student -> student.classId() != null && classIds.contains(student.classId()))
                .sorted(Comparator.comparing(UserDto::className, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(UserDto::fullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        Set<String> studentIds = studentRows.stream().map(UserDto::id).collect(Collectors.toSet());

        List<Grade> scopedGrades = grades.allGrades().stream()
                .filter(grade -> studentIds.contains(grade.getStudentId()))
                .filter(grade -> blank(filter.semesterId()) || filter.semesterId().equals(grade.getSemesterId()))
                .filter(grade -> blank(filter.subjectId()) || filter.subjectId().equals(grade.getSubjectId()))
                .toList();
        DateRange dates = semesterRange(filter.semesterId());
        List<AttendanceRecord> scopedAttendance = attendance.allRecords().stream()
                .filter(row -> studentIds.contains(row.getStudentId()))
                .filter(row -> dates.includes(row.getDate()))
                .toList();
        List<Assignment> scopedAssignments = assignments.list(null, null, null, false).stream()
                .filter(row -> classIds.contains(row.getClassId()))
                .filter(row -> blank(filter.subjectId()) || filter.subjectId().equals(row.getSubjectId()))
                .toList();
        Set<String> assignmentIds = scopedAssignments.stream().map(Assignment::getId).collect(Collectors.toSet());
        Map<String, List<Grade>> gradesByStudent = scopedGrades.stream()
                .collect(Collectors.groupingBy(Grade::getStudentId));
        Map<String, List<AttendanceRecord>> attendanceByStudent = scopedAttendance.stream()
                .collect(Collectors.groupingBy(AttendanceRecord::getStudentId));
        Map<String, List<Assignment>> assignmentsByClass = scopedAssignments.stream()
                .collect(Collectors.groupingBy(Assignment::getClassId));
        Map<String, List<AssignmentSubmission>> submissionsByStudent = assignments.allSubmissions().stream()
                .filter(submission -> assignmentIds.contains(submission.getAssignmentId()))
                .collect(Collectors.groupingBy(AssignmentSubmission::getStudentId));

        List<AcademicStudentRow> students = studentRows.stream().map(student -> {
            List<Grade> ownGrades = gradesByStudent.getOrDefault(student.id(), List.of());
            List<AttendanceRecord> ownAttendance = attendanceByStudent.getOrDefault(student.id(), List.of());
            List<Assignment> ownAssignments = assignmentsByClass.getOrDefault(student.classId(), List.of());
            List<AssignmentSubmission> ownSubmissions = submissionsByStudent.getOrDefault(student.id(), List.of());
            return new AcademicStudentRow(student.id(), student.studentCode(), student.fullName(),
                    student.classId(), student.className(), ownGrades.size(), averageScores(ownGrades),
                    countAttendance(ownAttendance, "PRESENT"), countAttendance(ownAttendance, "LATE"),
                    countAttendance(ownAttendance, "ABSENT_EXCUSED"), countAttendance(ownAttendance, "ABSENT_UNEXCUSED"),
                    attendanceRate(ownAttendance), ownAssignments.size(), ownSubmissions.size(),
                    (int) ownSubmissions.stream().filter(row -> "GRADED".equals(row.getStatus())).count());
        }).toList();

        List<AcademicSubjectRow> subjects = scopedGrades.stream()
                .collect(Collectors.groupingBy(Grade::getSubjectId, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> new AcademicSubjectRow(entry.getKey(),
                        entry.getValue().stream().map(Grade::getSubjectName).filter(Objects::nonNull).findFirst().orElse(entry.getKey()),
                        entry.getValue().size(), (int) entry.getValue().stream().map(Grade::getStudentId).distinct().count(),
                        averageScores(entry.getValue())))
                .sorted(Comparator.comparing(AcademicSubjectRow::subjectName, String.CASE_INSENSITIVE_ORDER)).toList();

        int submitted = students.stream().mapToInt(AcademicStudentRow::submittedAssignments).sum();
        int graded = students.stream().mapToInt(AcademicStudentRow::gradedAssignments).sum();
        AcademicReportSummary summary = new AcademicReportSummary(students.size(), classIds.size(), subjects.size(),
                scopedGrades.size(), averageScores(scopedGrades), scopedAttendance.size(), attendanceRate(scopedAttendance),
                scopedAssignments.size(), submitted, graded);
        return new AcademicReportResponse(filter, summary, students, subjects, Instant.now());
    }

    private DateRange semesterRange(String semesterId) {
        if (blank(semesterId)) return new DateRange(null, null);
        Semester semester = structure.listSemesters(null).stream()
                .filter(item -> semesterId.equals(item.getId())).findFirst().orElse(null);
        return semester == null ? new DateRange(null, null)
                : new DateRange(semester.getStartDate(), semester.getEndDate());
    }
    private int countAttendance(List<AttendanceRecord> rows, String status) {
        return (int) rows.stream().filter(row -> status.equals(row.getStatus())).count();
    }
    private Double averageScores(List<Grade> rows) {
        return round(rows.stream().map(Grade::getScore).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
    }
    private Double attendanceRate(List<AttendanceRecord> rows) {
        if (rows.isEmpty()) return null;
        long attended = rows.stream().filter(row -> "PRESENT".equals(row.getStatus()) || "LATE".equals(row.getStatus())).count();
        return round(attended * 100.0 / rows.size());
    }
    private Double round(double value) { return Double.isNaN(value) ? null : Math.round(value * 10.0) / 10.0; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private record DateRange(LocalDate from, LocalDate to) {
        boolean includes(LocalDate value) {
            return value != null && (from == null || !value.isBefore(from)) && (to == null || !value.isAfter(to));
        }
    }
}
