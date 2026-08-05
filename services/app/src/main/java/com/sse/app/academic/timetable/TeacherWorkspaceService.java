package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

import static com.sse.app.academic.timetable.TeacherWorkspaceDtos.*;

@Service
@RequiredArgsConstructor
public class TeacherWorkspaceService {
    private final JdbcTemplate jdbc;
    private final StructureService structure;
    private final TeacherLoadRegistrationRepository registrations;

    public WorkspaceContext context(String teacherId) {
        List<HomeroomClass> homeroomClasses = jdbc.query("""
                select c.id,c.code,c.name,count(u.id) student_count
                from classes c
                left join users u on u.class_id=c.id and u.role='STUDENT' and u.status='ACTIVE'
                left join academic_years ay on ay.id=c.academic_year_id
                where c.homeroom_teacher_id=? and (c.status is null or c.status='ACTIVE')
                  and (ay.status is null or ay.status in ('ACTIVE','PLANNED'))
                group by c.id,c.code,c.name
                order by c.code
                """, (rs, rowNum) -> new HomeroomClass(rs.getString("id"), rs.getString("code"),
                rs.getString("name"), rs.getInt("student_count")), teacherId);

        int teachingClassCount = number("""
                select count(distinct ta.class_id) from teaching_assignments ta
                left join semesters s on s.id=ta.semester_id
                where ta.teacher_id=? and (ta.status is null or ta.status='ACTIVE')
                  and (s.status is null or s.status in ('ACTIVE','PLANNED'))
                """, teacherId);
        int invigilationDutyCount = number("""
                select count(*) from exam_rooms er
                join exam_schedules es on es.id=er.schedule_id
                join exam_periods ep on ep.id=es.exam_period_id
                where (er.proctor_one_id=? or er.proctor_two_id=?)
                  and ep.status in ('CONFIRMED','COMPLETED')
                  and ep.end_date >= current_date - 7
                """, teacherId, teacherId);
        int gradingDutyCount = number("""
                select count(*) from exam_grading_assignments ega
                join exam_periods ep on ep.id=ega.exam_period_id
                where ega.teacher_id=? and ep.status in ('CONFIRMED','COMPLETED')
                  and ep.end_date >= current_date - 30
                """, teacherId);
        int pendingReviewCount = number("""
                select count(distinct rr.id) from exam_review_requests rr
                join exam_results result on result.id=rr.result_id
                join users student on student.id=result.student_id
                join exam_grading_assignments ega on ega.exam_period_id=rr.exam_period_id
                  and ega.schedule_id=result.schedule_id and ega.class_id=student.class_id
                where ega.teacher_id=? and rr.status in ('PENDING','IN_REVIEW')
                """, teacherId);

        Semester semester = currentOperationalSemester();
        TeacherLoadRegistration registration = semester == null ? null : registrations
                .findByTeacherIdAndSemesterId(teacherId, semester.getId()).orElse(null);
        String registrationStatus = registration == null ? null : registration.getStatus();

        return new WorkspaceContext(!homeroomClasses.isEmpty(), homeroomClasses, teachingClassCount,
                invigilationDutyCount + gradingDutyCount + pendingReviewCount > 0,
                invigilationDutyCount, gradingDutyCount, pendingReviewCount,
                semester == null ? null : semester.getId(), semester == null ? null : semester.getName(),
                false, false, false, null, null,
                registrationStatus);
    }

    private Semester currentOperationalSemester() {
        return structure.listYears().stream()
                .filter(year -> "ACTIVE".equals(year.getStatus()) || "PLANNED".equals(year.getStatus()))
                .flatMap(year -> structure.listSemesters(year.getId()).stream())
                .filter(semester -> !"CLOSED".equals(semester.getStatus()))
                .sorted(Comparator
                        .comparing((Semester semester) -> !"ACTIVE".equals(semester.getStatus()))
                        .thenComparing(Semester::getStartDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst().orElse(null);
    }

    private int number(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
