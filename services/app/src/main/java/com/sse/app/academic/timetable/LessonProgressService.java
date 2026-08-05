package com.sse.app.academic.timetable;

import com.sse.app.academic.planning.AcademicCurriculumItem;
import com.sse.app.academic.planning.AcademicPlanningService;
import com.sse.app.academic.planning.AcademicTrainingPlan;
import com.sse.app.academic.planning.AcademicTrainingPlanSubject;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.SchoolHoliday;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentRepository;
import com.sse.app.academic.timetable.TimetableDtos.ClassProgressRow;
import com.sse.app.academic.timetable.TimetableDtos.LessonProgressRequest;
import com.sse.app.academic.timetable.TimetableDtos.MakeupProposalRequest;
import com.sse.app.academic.timetable.TimetableDtos.ProgressComparison;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LessonProgressService {
    private final ClassLessonProgressRepository progress;
    private final TimetableMakeupProposalRepository makeup;
    private final TimetableScheduleRepository schedules;
    private final TimetableRepository liveSlots;
    private final StructureService structure;
    private final TeachingAssignmentRepository assignments;
    private final AcademicPlanningService planning;
    private final DomainEventPublisher events;
    private final TimetablePlanSourceService planSources;

    public LessonProgressService(
            ClassLessonProgressRepository progress,
            TimetableMakeupProposalRepository makeup,
            TimetableScheduleRepository schedules,
            TimetableRepository liveSlots,
            StructureService structure,
            TeachingAssignmentRepository assignments,
            AcademicPlanningService planning,
            DomainEventPublisher events,
            TimetablePlanSourceService planSources) {
        this.progress = progress;
        this.makeup = makeup;
        this.schedules = schedules;
        this.liveSlots = liveSlots;
        this.structure = structure;
        this.assignments = assignments;
        this.planning = planning;
        this.events = events;
        this.planSources = planSources;
    }

    public List<AcademicCurriculumItem> curriculum(
            String classId, String semesterId, String subjectId,
            CurrentUser actor) {
        SchoolClass schoolClass = structure.getClass(classId);
        assertTeachingScope(actor, classId, subjectId, semesterId);
        TimetablePlanSourceService.PlanSnapshot source = progressPlanSource(
                schoolClass, semesterId);
        return source == null
                ? planning.curriculumLessons(schoolClass.getAcademicYearId(),
                        schoolClass.getGradeLevel(), semesterId, subjectId)
                : planning.curriculumLessonsByPlan(
                        source.planId(), semesterId, subjectId);
    }

    public List<ClassLessonProgress> classProgress(
            String classId, String semesterId, CurrentUser actor) {
        if (actor.isTeacher()) {
            boolean assigned = assignments.findByTeacherIdAndStatus(
                    actor.id(), "ACTIVE").stream()
                    .anyMatch(item -> classId.equals(item.getClassId())
                            && semesterId.equals(item.getSemesterId()));
            if (!assigned) throw ApiException.forbidden(
                    "Giáo viên không được phân công tại lớp này");
        }
        return progress.findByClassIdAndSemesterIdOrderByLessonDateDesc(
                classId, semesterId);
    }

    @Transactional
    public ClassLessonProgress save(LessonProgressRequest request,
                                    CurrentUser actor) {
        SchoolClass schoolClass = structure.getClass(request.classId());
        Semester semester = structure.getSemester(request.semesterId());
        if (!schoolClass.getAcademicYearId().equals(semester.getAcademicYearId())) {
            throw ApiException.badRequest("Lớp và học kỳ không cùng năm học");
        }
        assertTeachingScope(actor, request.classId(), request.subjectId(),
                request.semesterId());
        AcademicCurriculumItem item = planning.getCurriculumItem(
                request.curriculumItemId());
        if (!"LESSON".equals(item.getItemType())) {
            throw ApiException.badRequest("Tiến độ phải gắn với một Bài học");
        }
        AcademicTrainingPlanSubject planSubject = planning.getPlanSubject(
                item.getPlanSubjectId());
        AcademicTrainingPlan plan = planning.getPlan(planSubject.getPlanId());
        TimetablePlanSourceService.PlanSnapshot source = progressPlanSource(
                schoolClass, request.semesterId());
        if (!Set.of("PUBLISHED", "LOCKED").contains(plan.getStatus())
                || !schoolClass.getAcademicYearId().equals(plan.getAcademicYearId())
                || !schoolClass.getGradeLevel().equals(plan.getGradeLevel())
                || !request.semesterId().equals(planSubject.getSemesterId())
                || !request.subjectId().equals(planSubject.getSubjectId())
                || (source != null && !source.planId().equals(plan.getId()))) {
            throw ApiException.badRequest(
                    "Bài học không thuộc kế hoạch đã công bố của lớp");
        }
        if (request.plannedPeriods() < 1
                || request.completedPeriods() < 0
                || request.completedPeriods() > request.plannedPeriods()) {
            throw ApiException.badRequest("Số tiết hoàn thành không hợp lệ");
        }
        String status = normalizeProgressStatus(request.status(),
                request.completedPeriods(), request.plannedPeriods());
        Instant now = Instant.now();
        ClassLessonProgress row = progress
                .findByClassIdAndSubjectIdAndSemesterIdAndCurriculumItemIdAndLessonDate(
                        request.classId(), request.subjectId(), request.semesterId(),
                        request.curriculumItemId(), request.lessonDate())
                .orElseGet(() -> ClassLessonProgress.builder()
                        .id(request.id() == null || request.id().isBlank()
                                ? Ids.gen("prog") : request.id())
                        .academicYearId(schoolClass.getAcademicYearId())
                        .semesterId(request.semesterId())
                        .classId(request.classId()).subjectId(request.subjectId())
                        .curriculumItemId(request.curriculumItemId())
                        .lessonDate(request.lessonDate())
                        .teacherId(actor.id()).createdAt(now).build());
        row.setPlannedPeriods(request.plannedPeriods());
        row.setCompletedPeriods(request.completedPeriods());
        row.setStatus(status);
        row.setNotes(request.notes());
        row.setTeacherId(actor.id());
        row.setSourcePlanId(plan.getId());
        row.setSourcePlanVersion(plan.getVersionNumber());
        row.setUpdatedAt(now);
        return progress.save(row);
    }

    public ProgressComparison compare(String academicYearId, String semesterId,
                                      String gradeLevel, String subjectId) {
        String grade = normalizeGrade(gradeLevel);
        List<SchoolClass> classes = structure.listClasses(academicYearId, grade);
        Set<String> classIds = classes.stream().map(SchoolClass::getId)
                .collect(Collectors.toSet());
        TimetableSchedule threshold = publishedSchedule(semesterId, grade);
        TimetablePlanSourceService.PlanSnapshot source = threshold == null
                ? null : planSources.sourceForGrade(planSources.parse(threshold), grade);
        List<ClassLessonProgress> all = progress
                .findBySemesterIdAndSubjectIdOrderByLessonDateAsc(
                        semesterId, subjectId).stream()
                .filter(item -> classIds.contains(item.getClassId()))
                .filter(item -> source == null || item.getSourcePlanId() == null
                        || source.planId().equals(item.getSourcePlanId()))
                .filter(item -> !"CANCELLED".equals(item.getStatus()))
                .toList();
        Map<String, List<ClassLessonProgress>> byClass = all.stream()
                .collect(Collectors.groupingBy(ClassLessonProgress::getClassId));
        Map<String, String> lessonTitles = all.stream()
                .map(ClassLessonProgress::getCurriculumItemId).distinct()
                .collect(Collectors.toMap(Function.identity(), id ->
                        planning.getCurriculumItem(id).getTitle()));
        int maxPeriods = byClass.values().stream()
                .mapToInt(rows -> rows.stream()
                        .mapToInt(ClassLessonProgress::getCompletedPeriods).sum())
                .max().orElse(0);
        int maxLessons = byClass.values().stream()
                .mapToInt(rows -> (int) rows.stream()
                        .filter(item -> "COMPLETED".equals(item.getStatus()))
                        .map(ClassLessonProgress::getCurriculumItemId)
                        .distinct().count()).max().orElse(0);
        LocalDate latestDate = all.stream().map(ClassLessonProgress::getLessonDate)
                .max(LocalDate::compareTo).orElse(null);
        LocalDate earliestLatest = byClass.values().stream()
                .map(rows -> rows.stream().map(ClassLessonProgress::getLessonDate)
                        .max(LocalDate::compareTo).orElse(null))
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo).orElse(null);
        int allowedDays = threshold == null ? 2 : threshold.getMaxProgressGapDays();
        int allowedPeriods = threshold == null ? 2 : threshold.getMaxProgressGapPeriods();
        int allowedLessons = threshold == null ? 1 : threshold.getMaxCurriculumGapLessons();
        Set<LocalDate> holidays = holidayDates(academicYearId);
        List<ClassProgressRow> rows = new ArrayList<>();
        for (SchoolClass schoolClass : classes) {
            List<ClassLessonProgress> classRows = byClass.getOrDefault(
                    schoolClass.getId(), List.of());
            int periods = classRows.stream()
                    .mapToInt(ClassLessonProgress::getCompletedPeriods).sum();
            int lessons = (int) classRows.stream()
                    .filter(item -> "COMPLETED".equals(item.getStatus()))
                    .map(ClassLessonProgress::getCurriculumItemId).distinct().count();
            ClassLessonProgress latest = classRows.stream()
                    .max(Comparator.comparing(ClassLessonProgress::getLessonDate))
                    .orElse(null);
            int dayLag = latestDate == null || latest == null ? 0
                    : teachingDaysBetween(latest.getLessonDate(), latestDate, holidays);
            int periodLag = maxPeriods - periods;
            int lessonLag = maxLessons - lessons;
            rows.add(new ClassProgressRow(schoolClass.getId(), schoolClass.getCode(),
                    periods, lessons, latest == null ? null : latest.getLessonDate(),
                    latest == null ? null
                            : lessonTitles.get(latest.getCurriculumItemId()),
                    dayLag, periodLag, lessonLag,
                    dayLag > allowedDays || periodLag > allowedPeriods
                            || lessonLag > allowedLessons));
        }
        int maxDayGap = latestDate == null || earliestLatest == null ? 0
                : (int) ChronoUnit.DAYS.between(earliestLatest, latestDate);
        int teachingGap = latestDate == null || earliestLatest == null ? 0
                : teachingDaysBetween(earliestLatest, latestDate, holidays);
        int minPeriods = rows.stream().mapToInt(ClassProgressRow::completedPeriods)
                .min().orElse(0);
        int minLessons = rows.stream().mapToInt(ClassProgressRow::completedLessons)
                .min().orElse(0);
        int periodGap = maxPeriods - minPeriods;
        int lessonGap = maxLessons - minLessons;
        List<String> warnings = rows.stream().filter(ClassProgressRow::delayed)
                .map(row -> row.classCode() + " chậm " + row.dayLag()
                        + " ngày học, " + row.periodLag() + " tiết và "
                        + row.lessonLag() + " bài").toList();
        return new ProgressComparison(academicYearId, semesterId, grade,
                source == null ? null : source.planId(),
                source == null ? null : source.versionNumber(),
                subjectId, maxDayGap, teachingGap, periodGap, lessonGap,
                allowedDays, allowedPeriods, allowedLessons,
                warnings.isEmpty(), rows, warnings);
    }

    @Transactional
    public List<TimetableMakeupProposal> generateMakeup(
            String scheduleId, MakeupProposalRequest request) {
        TimetableSchedule schedule = schedules.findById(scheduleId)
                .orElseThrow(() -> ApiException.notFound("Bản thời khóa biểu"));
        if (!"PUBLISHED".equals(schedule.getStatus())) {
            throw ApiException.conflict("Chỉ tạo lịch dạy bù từ lịch đang áp dụng");
        }
        if (request.toDate().isBefore(request.fromDate())) {
            throw ApiException.badRequest("Khoảng ngày không hợp lệ");
        }
        Set<LocalDate> holidays = holidayDates(schedule.getAcademicYearId());
        List<TimetableSlot> allRecurringSlots = liveSlots.findBySemesterId(
                schedule.getSemesterId());
        List<TimetableSlot> slots = allRecurringSlots.stream()
                .filter(item -> scheduleId.equals(item.getSourceScheduleId()))
                .toList();
        List<TimetableMakeupProposal> reservedMakeup = makeup.findAll().stream()
                .filter(item -> Set.of("PROPOSED", "APPROVED").contains(item.getStatus()))
                .collect(Collectors.toCollection(ArrayList::new));
        for (LocalDate date = request.fromDate(); !date.isAfter(request.toDate());
             date = date.plusDays(1)) {
            if (!holidays.contains(date)) continue;
            String day = dayCode(date.getDayOfWeek());
            for (TimetableSlot slot : slots) {
                if (!day.equals(slot.getDayOfWeek())
                        || makeup.existsByScheduleIdAndClassIdAndMissedDateAndMissedPeriodNo(
                                scheduleId, slot.getClassId(), date, slot.getPeriodNo())) continue;
                MakeupSlot proposed = nextAvailableMakeup(date, slot,
                        allRecurringSlots, reservedMakeup, holidays,
                        schedule.getFirstPeriod(), schedule.getLastPeriod());
                TimetableMakeupProposal saved = makeup.save(TimetableMakeupProposal.builder()
                        .id(Ids.gen("makeup")).scheduleId(scheduleId)
                        .classId(slot.getClassId()).subjectId(slot.getSubjectId())
                        .teacherId(slot.getTeacherId()).roomCode(slot.getRoomCode())
                        .missedDate(date).missedPeriodNo(slot.getPeriodNo())
                        .proposedDate(proposed == null ? null : proposed.date())
                        .proposedPeriodNo(proposed == null ? null : proposed.periodNo())
                        .reason("Nghỉ theo lịch nhà trường")
                        .status(proposed == null ? "UNSCHEDULED" : "PROPOSED")
                        .createdAt(Instant.now()).build());
                reservedMakeup.add(saved);
            }
        }
        return listMakeup(scheduleId);
    }

    public List<TimetableMakeupProposal> listMakeup(String scheduleId) {
        return makeup.findByScheduleIdOrderByMissedDateAscMissedPeriodNoAsc(
                scheduleId);
    }

    @Transactional
    public TimetableMakeupProposal reviewMakeup(String id, String status,
                                                String reason, String actorId) {
        TimetableMakeupProposal row = makeup.findById(id)
                .orElseThrow(() -> ApiException.notFound("Đề xuất dạy bù"));
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVED", "REJECTED").contains(normalized)) {
            throw ApiException.badRequest("Trạng thái duyệt không hợp lệ");
        }
        if ("APPROVED".equals(normalized) && row.getProposedDate() == null) {
            throw ApiException.conflict("Đề xuất chưa tìm được thời gian dạy bù");
        }
        if ("REJECTED".equals(normalized) && (reason == null || reason.isBlank())) {
            throw ApiException.badRequest("Vui lòng nhập lý do yêu cầu điều chỉnh lịch dạy bù");
        }
        if ("APPROVED".equals(normalized)) assertMakeupAvailable(row);
        row.setStatus(normalized);
        row.setReviewNote("REJECTED".equals(normalized) ? reason.trim() : null);
        row.setReviewedBy(actorId);
        row.setReviewedAt(Instant.now());
        TimetableMakeupProposal saved = makeup.save(row);
        if ("APPROVED".equals(normalized)) {
            String classCode = structure.getClass(saved.getClassId()).getCode();
            String subjectName = structure.subjectName(saved.getSubjectId());
            events.publish("academic.timetable.makeup_approved", actorId,
                    "timetable_makeup", saved.getId(),
                    Map.of("classId", saved.getClassId(),
                            "teacherIds", List.of(saved.getTeacherId()),
                            "message", "Lớp " + classCode + " học bù " + subjectName
                                    + " ngày "
                                    + saved.getProposedDate() + ", tiết "
                                    + saved.getProposedPeriodNo()));
        }
        return saved;
    }

    private void assertTeachingScope(CurrentUser actor, String classId,
                                     String subjectId, String semesterId) {
        if (actor.isAdmin()) return;
        if (!actor.isTeacher() || !assignments
                .existsByTeacherIdAndClassIdAndSubjectIdAndSemesterIdAndStatus(
                        actor.id(), classId, subjectId, semesterId, "ACTIVE")) {
            throw ApiException.forbidden(
                    "Giáo viên không được phân công dạy môn này tại lớp đã chọn");
        }
    }

    private String normalizeProgressStatus(String status, int completed,
                                           int planned) {
        String normalized = status == null || status.isBlank()
                ? (completed == planned ? "COMPLETED"
                    : completed == 0 ? "PLANNED" : "PARTIAL")
                : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PLANNED", "PARTIAL", "COMPLETED", "CANCELLED")
                .contains(normalized)) {
            throw ApiException.badRequest("Trạng thái tiến độ không hợp lệ");
        }
        return normalized;
    }

    private TimetableSchedule publishedSchedule(String semesterId, String grade) {
        return schedules
                .findFirstBySemesterIdAndScopeGradeLevelAndStatusOrderByPublishedAtDesc(
                        semesterId, grade, "PUBLISHED")
                .or(() -> schedules
                        .findFirstBySemesterIdAndScopeGradeLevelIsNullAndStatusOrderByPublishedAtDesc(
                                semesterId, "PUBLISHED"))
                .orElse(null);
    }

    private TimetablePlanSourceService.PlanSnapshot progressPlanSource(
            SchoolClass schoolClass, String semesterId) {
        TimetableSchedule schedule = publishedSchedule(
                semesterId, schoolClass.getGradeLevel());
        if (schedule == null || planSources.parse(schedule).isEmpty()) return null;
        return planSources.sourceForGrade(
                planSources.parse(schedule), schoolClass.getGradeLevel());
    }

    private void assertMakeupAvailable(TimetableMakeupProposal candidate) {
        String day = dayCode(candidate.getProposedDate().getDayOfWeek());
        int period = candidate.getProposedPeriodNo();
        boolean recurringConflict = liveSlots.findByDayOfWeekAndPeriodNo(day, period)
                .stream().anyMatch(slot -> candidate.getClassId().equals(slot.getClassId())
                        || candidate.getTeacherId().equals(slot.getTeacherId())
                        || (candidate.getRoomCode() != null
                        && candidate.getRoomCode().equals(slot.getRoomCode())));
        boolean makeupConflict = makeup.findAll().stream()
                .filter(row -> !row.getId().equals(candidate.getId()))
                .filter(row -> "APPROVED".equals(row.getStatus()))
                .filter(row -> candidate.getProposedDate().equals(row.getProposedDate()))
                .filter(row -> period == row.getProposedPeriodNo())
                .anyMatch(row -> candidate.getClassId().equals(row.getClassId())
                        || candidate.getTeacherId().equals(row.getTeacherId())
                        || (candidate.getRoomCode() != null
                        && candidate.getRoomCode().equals(row.getRoomCode())));
        if (recurringConflict || makeupConflict) {
            throw ApiException.conflict(
                    "Ca dạy bù vừa bị chiếm bởi lớp, giáo viên hoặc phòng khác; hãy tạo lại đề xuất");
        }
    }

    private Set<LocalDate> holidayDates(String academicYearId) {
        Set<LocalDate> result = new HashSet<>();
        for (SchoolHoliday holiday : structure.listHolidays(academicYearId)) {
            LocalDate end = holiday.getEndDate() == null
                    ? holiday.getDate() : holiday.getEndDate();
            for (LocalDate date = holiday.getDate(); !date.isAfter(end);
                 date = date.plusDays(1)) result.add(date);
        }
        return result;
    }

    private int teachingDaysBetween(LocalDate start, LocalDate end,
                                    Set<LocalDate> holidays) {
        int count = 0;
        for (LocalDate date = start.plusDays(1); !date.isAfter(end);
             date = date.plusDays(1)) {
            if (date.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !holidays.contains(date)) count++;
        }
        return count;
    }

    private MakeupSlot nextAvailableMakeup(LocalDate missed,
            TimetableSlot missedSlot, List<TimetableSlot> recurring,
            List<TimetableMakeupProposal> reservedMakeup,
            Set<LocalDate> holidays, int firstPeriod, int lastPeriod) {
        for (LocalDate date = missed.plusDays(1); !date.isAfter(missed.plusDays(21));
             date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.contains(date)) continue;
            List<Integer> candidatePeriods = new ArrayList<>();
            if (missedSlot.getPeriodNo() >= firstPeriod
                    && missedSlot.getPeriodNo() <= lastPeriod) {
                candidatePeriods.add(missedSlot.getPeriodNo());
            }
            for (int period = firstPeriod; period <= lastPeriod; period++) {
                if (period != missedSlot.getPeriodNo()) candidatePeriods.add(period);
            }
            String candidateDay = dayCode(date.getDayOfWeek());
            LocalDate candidateDate = date;
            for (int period : candidatePeriods) {
                final int candidatePeriod = period;
                boolean busy = recurring.stream().anyMatch(slot ->
                        candidateDay.equals(slot.getDayOfWeek())
                                && candidatePeriod == slot.getPeriodNo()
                                && (missedSlot.getClassId().equals(slot.getClassId())
                                || missedSlot.getTeacherId().equals(slot.getTeacherId())
                                || (missedSlot.getRoomCode() != null
                                && missedSlot.getRoomCode().equals(slot.getRoomCode()))));
                boolean makeupBusy = reservedMakeup.stream()
                        .filter(item -> candidateDate.equals(item.getProposedDate()))
                        .filter(item -> item.getProposedPeriodNo() != null
                                && candidatePeriod == item.getProposedPeriodNo())
                        .anyMatch(item -> missedSlot.getClassId().equals(item.getClassId())
                                || missedSlot.getTeacherId().equals(item.getTeacherId())
                                || (missedSlot.getRoomCode() != null
                                && missedSlot.getRoomCode().equals(item.getRoomCode())));
                if (!busy && !makeupBusy) return new MakeupSlot(candidateDate, period);
            }
        }
        return null;
    }

    private record MakeupSlot(LocalDate date, int periodNo) {}

    private String normalizeGrade(String value) {
        if (value == null) throw ApiException.badRequest("Phải chọn khối");
        String grade = value.trim().toUpperCase(Locale.ROOT);
        if (grade.matches("10|11|12")) grade = "K" + grade;
        if (!Set.of("K10", "K11", "K12").contains(grade))
            throw ApiException.badRequest("Khối không hợp lệ");
        return grade;
    }

    private String dayCode(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "MON"; case TUESDAY -> "TUE";
            case WEDNESDAY -> "WED"; case THURSDAY -> "THU";
            case FRIDAY -> "FRI"; case SATURDAY -> "SAT";
            case SUNDAY -> "SUN";
        };
    }
}
