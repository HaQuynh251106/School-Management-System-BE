package com.sse.app.academic.conduct;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import com.sse.app.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

import static com.sse.app.academic.conduct.ConductDtos.*;
import static com.sse.app.academic.structure.StructureDtos.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:conduct-evaluation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sse.seed.enabled=false"
})
@ActiveProfiles("demo")
@Transactional
class ConductEvaluationIntegrationTest {
    @Autowired ConductEvaluationService conduct;
    @Autowired StructureService structure;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private final CurrentUser homeroom = new CurrentUser("teacher-conduct", "gv.conduct", "TEACHER");
    private final CurrentUser academic = new CurrentUser("academic-conduct", "giaovu.conduct", "ACADEMIC_STAFF");
    private String yearId;
    private String classId;

    @BeforeEach
    void setUp() {
        LocalDate start = LocalDate.of(2025, 8, 1);
        var year = structure.createYear(new CreateAcademicYearRequest(
                "year-conduct", "2025-2026-RL", "Năm học kiểm thử rèn luyện",
                start, LocalDate.of(2026, 8, 31), "PLANNED"));
        yearId = year.getId();
        structure.changeYearStatus(yearId, "ACTIVE");
        var schoolClass = structure.createClass(new CreateClassRequest(
                "class-conduct", "10RL1", "Lớp rèn luyện", "K10", yearId, null,
                "MORNING", 45, null));
        classId = schoolClass.getId();
        users.save(User.builder().id(homeroom.id()).username(homeroom.username()).passwordHash("x")
                .fullName("Nguyễn Minh Giáo").role("TEACHER").status("ACTIVE").teacherCode("GV-RL").build());
        users.save(User.builder().id(academic.id()).username(academic.username()).passwordHash("x")
                .fullName("Trần Thu Giáo Vụ").role("ACADEMIC_STAFF").status("ACTIVE").build());
        users.save(User.builder().id("student-conduct").username("hs.conduct").passwordHash("x")
                .fullName("Lê An Nhiên").role("STUDENT").status("ACTIVE").studentCode("HS-RL")
                .classId(classId).className("10RL1").studentStatus("ENROLLED").build());
        structure.assignHomeroomTeacher(classId, homeroom.id(), "Nguyễn Minh Giáo", academic.id());
        structure.recordEnrollment("student-conduct", classId);

        for (int index = 0; index < 8; index++) {
            insertAttendance("attendance-present-" + index, start.plusDays(index), "PRESENT");
        }
        insertAttendance("attendance-excused-1", start.plusDays(8), "ABSENT_EXCUSED");
        insertAttendance("attendance-excused-2", start.plusDays(9), "ABSENT_EXCUSED");
        jdbc.update("insert into assignments(id,allow_late,created_at,deadline,class_id,status,title) values(?,?,?,?,?,?,?)",
                "assignment-conduct", false, start.atStartOfDay(), start.plusDays(20).atStartOfDay(),
                classId, "PUBLISHED", "Bài tập trách nhiệm");
        jdbc.update("insert into assignment_submissions(id,assignment_id,student_id,submitted_at,status) values(?,?,?,?,?)",
                "submission-conduct", "assignment-conduct", "student-conduct",
                start.plusDays(15).atStartOfDay(), "SUBMITTED");
    }

    @Test
    void proposesTransparentGradeAndNeverPenalizesExcusedLeave() {
        EvaluationView result = conduct.evaluate(yearId, null, "student-conduct", homeroom);

        assertThat(result.readiness()).isEqualTo("READY");
        assertThat(result.suggestedGrade()).isEqualTo("GOOD");
        assertThat(result.suggestedScore()).isEqualTo(95.5d);
        CriterionBreakdown attendance = result.criteria().stream()
                .filter(item -> item.code().equals("ATTENDANCE")).findFirst().orElseThrow();
        assertThat(attendance.rawScore()).isEqualTo(100d);
        assertThat(attendance.evidence()).anySatisfy(item -> {
            assertThat(item.sourceRef()).isEqualTo("ATTENDANCE:EXCUSED");
            assertThat(item.impactPoints()).isZero();
            assertThat(item.description()).contains("không bị trừ");
        });
    }

    @Test
    void requiresReasonForHomeroomOverrideAndKeepsAuditHistory() {
        assertThatThrownBy(() -> conduct.decide(yearId, "student-conduct",
                new DecisionRequest(null, "FAIR", null), homeroom))
                .isInstanceOf(ApiException.class).hasMessageContaining("ghi rõ lý do");

        EvaluationView decided = conduct.decide(yearId, "student-conduct",
                new DecisionRequest(null, "FAIR", "GVCN xem xét hoàn cảnh và quá trình tiến bộ"), homeroom);
        assertThat(decided.finalGrade()).isEqualTo("FAIR");
        assertThat(decided.overrideReason()).contains("hoàn cảnh");
        assertThat(decided.audits()).anyMatch(item -> item.action().equals("HOMEROOM_DECIDED"));
    }

    @Test
    void deduplicatesEvidenceAndVersionsRules() {
        SaveEvidenceRequest evidence = new SaveEvidenceRequest(yearId, null, classId, "student-conduct",
                "PARTICIPATION", 10, "Hỗ trợ hoạt động lớp", "Chủ động hỗ trợ tập thể",
                LocalDate.of(2025, 9, 1), "class-event-001");
        conduct.addEvidence(evidence, homeroom);
        assertThatThrownBy(() -> conduct.addEvidence(evidence, homeroom))
                .isInstanceOf(ApiException.class).hasMessageContaining("đã được ghi nhận");

        RuleSetView initial = conduct.activeRules(yearId, null);
        RuleSetView replaced = conduct.replaceRules(yearId,
                new SaveRuleSetRequest(null, 30, 30, 25, 15, 85, 70, 50, 8, 1), academic);
        assertThat(replaced.versionNo()).isEqualTo(initial.versionNo() + 1);
        assertThat(replaced.minParticipationEvidence()).isEqualTo(1);
        assertThat(conduct.evaluate(yearId, null, "student-conduct", homeroom).readiness()).isEqualTo("READY");
    }

    @Test
    void rejectsEvidenceFromUnrelatedTeacher() {
        users.save(User.builder().id("teacher-unrelated").username("gv.unrelated").passwordHash("x")
                .fullName("Giáo viên không phụ trách").role("TEACHER").status("ACTIVE").build());
        CurrentUser unrelated = new CurrentUser("teacher-unrelated", "gv.unrelated", "TEACHER");
        SaveEvidenceRequest evidence = new SaveEvidenceRequest(yearId, null, classId, "student-conduct",
                "DISCIPLINE", -5, "Ghi nhận không hợp lệ", null,
                LocalDate.of(2025, 9, 2), "unrelated-001");
        assertThatThrownBy(() -> conduct.addEvidence(evidence, unrelated))
                .isInstanceOf(ApiException.class).hasMessageContaining("lớp đang dạy hoặc chủ nhiệm");
    }

    private void insertAttendance(String id, LocalDate date, String status) {
        jdbc.update("insert into attendance_records(id,date,period_no,class_id,slot_id,status,student_id,subject_name) values(?,?,?,?,?,?,?,?)",
                id, date, 1, classId, "slot-" + id, status, "student-conduct", "Toán");
    }
}
