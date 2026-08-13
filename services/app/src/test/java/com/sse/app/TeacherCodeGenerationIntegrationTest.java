package com.sse.app;

import com.sse.app.identity.IdentityDtos.CreateUserRequest;
import com.sse.app.identity.UserService;
import com.sse.app.academic.structure.StructureDtos.CreateSubjectRequest;
import com.sse.app.academic.structure.StructureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:teacher-code-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sse.seed.enabled=false"
})
@ActiveProfiles("demo")
class TeacherCodeGenerationIntegrationTest {
    @Autowired UserService users;
    @Autowired StructureService structure;

    @BeforeEach
    void ensureSubjectCatalog() {
        if (structure.listSubjects().stream().noneMatch(subject -> subject.getId().equals("subject-auto-test"))) {
            structure.createSubject(new CreateSubjectRequest(
                    "subject-auto-test", "AUTO", "Môn kiểm thử", 1.0));
        }
    }

    @Test
    void backendGeneratesUniqueTeacherCodesAndIgnoresManualValues() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<String>> creates = IntStream.range(0, 12)
                    .mapToObj(index -> (Callable<String>) () -> users.create(teacher(index)).userCode())
                    .toList();

            List<String> codes = pool.invokeAll(creates).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            }).toList();

            assertThat(codes).hasSize(12).doesNotHaveDuplicates();
            assertThat(codes).allMatch(code -> code.matches("GV\\d{6}"));
            assertThat(codes).doesNotContain("GV-MANUAL");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void backendGeneratesImmutableCodesForAllSixRoles() {
        Map<String, String> expectedPatterns = Map.ofEntries(
                Map.entry("ADMIN", "AD\\d{6}"),
                Map.entry("ACADEMIC_STAFF", "GVU\\d{6}"),
                Map.entry("ACCOUNTANT", "KT\\d{6}"),
                Map.entry("TEACHER", "GV\\d{6}"),
                Map.entry("STUDENT", "HS\\d{6}"),
                Map.entry("PARENT", "PH\\d{6}"));

        expectedPatterns.forEach((role, pattern) -> {
            var created = users.create(account(role));
            assertThat(created.userCode()).matches(pattern);
            if ("TEACHER".equals(role)) {
                assertThat(created.teacherCode()).isEqualTo(created.userCode());
                assertThat(created.mainSubjectId()).isEqualTo("subject-auto-test");
                assertThat(created.mainSubject()).isEqualTo("Môn kiểm thử");
            }
            if ("STUDENT".equals(role)) {
                assertThat(created.studentCode()).isEqualTo(created.userCode());
            }
        });
    }

    @Test
    void teacherSubjectMustComeFromTheSchoolCatalog() {
        assertThatThrownBy(() -> users.create(teacher(99, "subject-not-in-school")))
                .hasMessageContaining("Môn học");
    }

    private CreateUserRequest account(String role) {
        String suffix = role.toLowerCase();
        return new CreateUserRequest(
                null, "auto." + suffix, "Account@12345", "Tài khoản " + role,
                role, suffix + "@example.test", "0910000000", null,
                "MANUAL-CODE", "Tên môn tự nhập",
                "TEACHER".equals(role) ? "subject-auto-test" : null,
                "MANUAL-STUDENT", null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private CreateUserRequest teacher(int index) {
        return teacher(index, "subject-auto-test");
    }

    private CreateUserRequest teacher(int index, String mainSubjectId) {
        return new CreateUserRequest(
                null,
                "teacher.auto." + index,
                "Teacher@12345",
                "Giáo viên tự động " + index,
                "TEACHER",
                "teacher." + index + "@example.test",
                "090000" + String.format("%04d", index),
                null,
                "GV-MANUAL",
                "Môn tự nhập không được dùng",
                mainSubjectId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
