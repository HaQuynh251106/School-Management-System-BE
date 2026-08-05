package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TeacherWorkloadPolicyService {
    public static final int STATUTORY_THPT_WEEKLY_PERIODS = 17;
    public static final int DEFAULT_TEACHING_WEEKS = 35;
    public static final int MAX_OVERTIME_PERCENT = 50;
    public static final int HOMEROOM_REDUCTION_PERIODS = 4;

    private final TeacherWorkloadPolicyRepository policies;
    private final TeacherWorkloadAdjustmentRepository adjustments;
    private final TeacherLoadRegistrationRepository registrations;
    private final StructureService structure;

    @Transactional
    public TeacherLoadRegistration ensureRegistration(String teacherId, String teacherName, String semesterId) {
        structure.getSemester(semesterId);
        Instant now = Instant.now();
        TeacherLoadRegistration item = registrations.findByTeacherIdAndSemesterId(teacherId, semesterId)
                .orElseGet(() -> TeacherLoadRegistration.builder().id(Ids.gen("tlr"))
                        .teacherId(teacherId).teacherName(teacherName).semesterId(semesterId)
                        .status("SYSTEM").createdAt(now).build());
        item.setTeacherName(teacherName);
        item.setStatus("SYSTEM");
        item.setUnavailableSlots(null);
        item.setPreferredGradeLevels(null);
        item.setPreferredDaysOff(null);
        item.setNote(null);
        item.setReviewNote(null);
        apply(item);
        item.setUpdatedAt(now);
        return registrations.save(item);
    }

    public WorkloadSnapshot snapshot(String teacherId, String semesterId) {
        Semester semester = structure.getSemester(semesterId);
        TeacherWorkloadPolicy policy = policyFor(semester.getAcademicYearId());
        if (!policy.isActive() || !overlaps(policy.getEffectiveFrom(), policy.getEffectiveTo(),
                semester.getStartDate(), semester.getEndDate())) {
            throw ApiException.conflict("Chính sách định mức tải dạy chưa có hiệu lực trong học kỳ đã chọn");
        }
        List<TeacherWorkloadAdjustment> approved = adjustments
                .findByTeacherIdAndAcademicYearIdAndStatus(teacherId, semester.getAcademicYearId(), "APPROVED")
                .stream().filter(item -> applies(item, semester)).toList();
        boolean homeroom = structure.listClasses(semester.getAcademicYearId(), null).stream()
                .anyMatch(item -> teacherId.equals(item.getHomeroomTeacherId()));
        int automaticHomeroomReduction = homeroom ? policy.getHomeroomReductionPeriods() : 0;
        int manualReduction = sum(approved, "REDUCTION");
        int converted = sum(approved, "CONVERSION");
        int approvedOvertime = sum(approved, "OVERTIME");
        int target = Math.max(0, policy.getBaseWeeklyPeriods()
                - automaticHomeroomReduction - manualReduction - converted);
        int overtimeLimit = policy.getBaseWeeklyPeriods() * policy.getMaxOvertimePercent() / 100;
        int effectiveOvertime = Math.min(approvedOvertime, overtimeLimit);
        int effectiveMaximum = target + effectiveOvertime;
        return new WorkloadSnapshot(policy.getBaseWeeklyPeriods(), automaticHomeroomReduction + manualReduction,
                converted, target, effectiveOvertime, target + overtimeLimit,
                effectiveMaximum, target * policy.getTeachingWeeks(), policy.getTeachingWeeks(), homeroom,
                policy.getSourceDocument(), approved);
    }

    public void apply(TeacherLoadRegistration registration) {
        WorkloadSnapshot value = snapshot(registration.getTeacherId(), registration.getSemesterId());
        registration.setBaseWeeklyPeriods(value.baseWeeklyPeriods());
        registration.setReductionWeeklyPeriods(value.reductionWeeklyPeriods());
        registration.setConvertedWeeklyPeriods(value.convertedWeeklyPeriods());
        registration.setApprovedOvertimeWeeklyPeriods(value.approvedOvertimeWeeklyPeriods());
        registration.setStandardWeeklyPeriods(value.targetDirectWeeklyPeriods());
        registration.setMinWeeklyPeriods(value.targetDirectWeeklyPeriods());
        registration.setMaxWeeklyPeriods(value.effectiveMaximumWeeklyPeriods());
        registration.setAnnualTargetPeriods(value.annualTargetPeriods());
        registration.setMaxDailyPeriods(TimetableRulePolicy.PERIODS_PER_DAY);
        registration.setMaxConsecutivePeriods(TimetableRulePolicy.PERIODS_PER_DAY);
    }

    public TeacherWorkloadPolicy policyFor(String academicYearId) {
        AcademicYear year = structure.getYear(academicYearId);
        return policies.findByAcademicYearId(academicYearId).orElseGet(() -> defaultPolicy(year));
    }

    @Transactional
    public TeacherWorkloadPolicy savePolicy(String academicYearId, int teachingWeeks,
                                            LocalDate effectiveFrom, LocalDate effectiveTo, String actorId) {
        if (teachingWeeks < 1 || teachingWeeks > 52) throw ApiException.badRequest("Số tuần giảng dạy không hợp lệ");
        if (effectiveFrom != null && effectiveTo != null && effectiveFrom.isAfter(effectiveTo)) {
            throw ApiException.badRequest("Ngày hiệu lực không được sau ngày kết thúc");
        }
        AcademicYear year = structure.getYear(academicYearId);
        LocalDate from = effectiveFrom == null ? year.getStartDate() : effectiveFrom;
        LocalDate to = effectiveTo == null ? year.getEndDate() : effectiveTo;
        if (year.getStartDate() != null && from != null && from.isBefore(year.getStartDate())
                || year.getEndDate() != null && to != null && to.isAfter(year.getEndDate())) {
            throw ApiException.badRequest("Thời gian áp dụng chính sách phải nằm trong phạm vi năm học");
        }
        Instant now = Instant.now();
        TeacherWorkloadPolicy item = policies.findByAcademicYearId(academicYearId)
                .orElseGet(() -> defaultPolicy(year));
        item.setTeachingWeeks(teachingWeeks);
        item.setEffectiveFrom(from);
        item.setEffectiveTo(to);
        item.setConfiguredBy(actorId);
        item.setUpdatedAt(now);
        if (item.getCreatedAt() == null) item.setCreatedAt(now);
        return policies.save(item);
    }

    public List<TeacherWorkloadAdjustment> listAdjustments(String academicYearId, String teacherId) {
        structure.getYear(academicYearId);
        return adjustments.findByAcademicYearIdOrderByCreatedAtDesc(academicYearId).stream()
                .filter(item -> teacherId == null || teacherId.isBlank() || teacherId.equals(item.getTeacherId()))
                .toList();
    }

    @Transactional
    public TeacherWorkloadAdjustment saveAdjustment(String teacherId, String academicYearId,
                                                     String category, String dutyType, String title,
                                                     int weeklyPeriods, LocalDate effectiveFrom,
                                                     LocalDate effectiveTo, String reason, String actorId) {
        structure.getYear(academicYearId);
        String normalizedCategory = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("REDUCTION", "CONVERSION", "OVERTIME").contains(normalizedCategory)) {
            throw ApiException.badRequest("Loại điều chỉnh định mức không hợp lệ");
        }
        if (weeklyPeriods < 1 || weeklyPeriods > STATUTORY_THPT_WEEKLY_PERIODS) {
            throw ApiException.badRequest("Số tiết điều chỉnh phải từ 1 đến 17");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw ApiException.badRequest("Cần ghi rõ căn cứ hoặc lý do điều chỉnh (ít nhất 5 ký tự)");
        }
        if (effectiveFrom != null && effectiveTo != null && effectiveFrom.isAfter(effectiveTo)) {
            throw ApiException.badRequest("Ngày bắt đầu không được sau ngày kết thúc");
        }
        if (normalizedCategory.equals("OVERTIME")
                && weeklyPeriods > STATUTORY_THPT_WEEKLY_PERIODS * MAX_OVERTIME_PERCENT / 100) {
            throw ApiException.badRequest("Dạy vượt trong tuần không được quá 50% định mức 17 tiết");
        }
        Instant now = Instant.now();
        return adjustments.save(TeacherWorkloadAdjustment.builder().id(Ids.gen("twa"))
                .teacherId(teacherId).academicYearId(academicYearId).category(normalizedCategory)
                .dutyType(dutyType == null || dutyType.isBlank() ? "OTHER" : dutyType.trim().toUpperCase(Locale.ROOT))
                .title(title == null || title.isBlank() ? "Điều chỉnh định mức" : title.trim())
                .weeklyPeriods(weeklyPeriods).effectiveFrom(effectiveFrom).effectiveTo(effectiveTo)
                .reason(reason == null ? null : reason.trim()).status("APPROVED")
                .approvedBy(actorId).approvedAt(now).createdAt(now).updatedAt(now).build());
    }

    @Transactional
    public TeacherWorkloadAdjustment revokeAdjustment(String id, String reason, String actorId) {
        TeacherWorkloadAdjustment item = adjustments.findById(id)
                .orElseThrow(() -> ApiException.notFound("Điều chỉnh định mức"));
        if (!"APPROVED".equals(item.getStatus())) {
            throw ApiException.conflict("Điều chỉnh này không còn hiệu lực để thu hồi");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw ApiException.badRequest("Cần ghi rõ lý do thu hồi (ít nhất 5 ký tự)");
        }
        item.setStatus("REVOKED");
        item.setRevokeReason(reason.trim());
        item.setRevokedBy(actorId);
        item.setRevokedAt(Instant.now());
        item.setUpdatedAt(Instant.now());
        return adjustments.save(item);
    }

    private TeacherWorkloadPolicy defaultPolicy(AcademicYear year) {
        Instant now = Instant.now();
        return TeacherWorkloadPolicy.builder().id(Ids.gen("twp")).academicYearId(year.getId())
                .schoolLevel("THPT").baseWeeklyPeriods(STATUTORY_THPT_WEEKLY_PERIODS)
                .teachingWeeks(DEFAULT_TEACHING_WEEKS).maxOvertimePercent(MAX_OVERTIME_PERCENT)
                .homeroomReductionPeriods(HOMEROOM_REDUCTION_PERIODS)
                .effectiveFrom(year.getStartDate()).effectiveTo(year.getEndDate())
                .sourceDocument("Thông tư 05/2025/TT-BGDĐT").active(true)
                .createdAt(now).updatedAt(now).build();
    }

    private static int sum(List<TeacherWorkloadAdjustment> values, String category) {
        return values.stream().filter(item -> category.equals(item.getCategory()))
                .mapToInt(TeacherWorkloadAdjustment::getWeeklyPeriods).sum();
    }

    private static boolean applies(TeacherWorkloadAdjustment item, Semester semester) {
        if (item.getEffectiveFrom() != null && semester.getEndDate() != null
                && item.getEffectiveFrom().isAfter(semester.getEndDate())) return false;
        return item.getEffectiveTo() == null || semester.getStartDate() == null
                || !item.getEffectiveTo().isBefore(semester.getStartDate());
    }

    private static boolean overlaps(LocalDate from, LocalDate to, LocalDate semesterFrom, LocalDate semesterTo) {
        if (from != null && semesterTo != null && from.isAfter(semesterTo)) return false;
        return to == null || semesterFrom == null || !to.isBefore(semesterFrom);
    }

    public record WorkloadSnapshot(
            int baseWeeklyPeriods, int reductionWeeklyPeriods, int convertedWeeklyPeriods,
            int targetDirectWeeklyPeriods, int approvedOvertimeWeeklyPeriods,
            int legalWeeklyCap, int effectiveMaximumWeeklyPeriods,
            int annualTargetPeriods, int teachingWeeks, boolean homeroomTeacher,
            String sourceDocument, List<TeacherWorkloadAdjustment> adjustments) {}
}
