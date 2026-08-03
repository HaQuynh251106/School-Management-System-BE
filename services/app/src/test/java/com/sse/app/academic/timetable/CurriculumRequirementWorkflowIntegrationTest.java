package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureDtos.CreateAcademicYearRequest;
import com.sse.app.academic.structure.StructureDtos.CreateClassRequest;
import com.sse.app.academic.structure.StructureDtos.CreateSubjectRequest;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.WorkloadPlanningDtos.AutoAssignmentRequest;
import com.sse.app.academic.timetable.WorkloadPlanningDtos.CopyCurriculumRequirementsRequest;
import com.sse.app.academic.timetable.WorkloadPlanningDtos.SaveCurriculumRequirementRequest;
import com.sse.app.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:curriculum-workflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sse.seed.enabled=false"
})
@ActiveProfiles("demo")
class CurriculumRequirementWorkflowIntegrationTest {
    @Autowired StructureService structure;
    @Autowired WorkloadPlanningService planning;

    @Test
    void detectsMissingSubjectsBlocksPlanningCopiesAndAuditsChanges() {
        var year = structure.createYear(new CreateAcademicYearRequest(
                "ay-curriculum", "2034-2035", "Năm học 2034-2035",
                LocalDate.of(2034, 8, 15), LocalDate.of(2035, 5, 31), "PLANNED"));
        structure.createClass(new CreateClassRequest(
                "class-curriculum", "10A1", "Lớp 10A1", "K10", year.getId(),
                null, "MORNING", 45, null));
        var math = structure.createSubject(new CreateSubjectRequest(
                "subject-curriculum-math", "MATH-CURR", "Toán", 1.0));
        var literature = structure.createSubject(new CreateSubjectRequest(
                "subject-curriculum-lit", "LIT-CURR", "Ngữ văn", 1.0));
        Semester first = structure.listSemesters(year.getId()).get(0);
        Semester second = structure.listSemesters(year.getId()).get(1);

        planning.saveRequirement(new SaveCurriculumRequirementRequest(
                first.getId(), "K10", math.getId(), 4), "academic-test");

        var incomplete = planning.curriculumReadiness(first.getId());
        assertThat(incomplete.complete()).isFalse();
        assertThat(incomplete.grades()).singleElement().satisfies(grade -> {
            assertThat(grade.configuredSubjectCount()).isEqualTo(1);
            assertThat(grade.expectedSubjectCount()).isEqualTo(2);
            assertThat(grade.missingSubjects()).extracting("subjectName").containsExactly("Ngữ văn");
        });
        assertThatThrownBy(() -> planning.plan(
                new AutoAssignmentRequest(first.getId(), false, false), "academic-test"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Ngữ văn");

        var literatureRequirement = planning.saveRequirement(new SaveCurriculumRequirementRequest(
                first.getId(), "K10", literature.getId(), 4), "academic-test");
        assertThat(planning.curriculumReadiness(first.getId()).complete()).isTrue();

        planning.copyRequirements(new CopyCurriculumRequirementsRequest(
                first.getId(), "K10", second.getId(), "K10", true), "academic-test");
        assertThat(planning.curriculumReadiness(second.getId()).complete()).isTrue();
        assertThat(planning.listRequirements(second.getId())).hasSize(2);

        planning.deleteRequirement(literatureRequirement.getId(), "academic-test");
        assertThat(planning.curriculumReadiness(first.getId()).complete()).isFalse();
        assertThat(planning.curriculumHistory(first.getId()))
                .extracting("action").contains("CREATED", "DELETED");
        assertThat(planning.curriculumHistory(second.getId()))
                .extracting("action").containsOnly("COPIED");
    }
}
