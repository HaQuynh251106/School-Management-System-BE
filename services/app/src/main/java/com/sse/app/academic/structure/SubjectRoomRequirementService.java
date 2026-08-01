package com.sse.app.academic.structure;

import com.sse.app.academic.structure.SubjectRoomRequirementDtos.*;
import com.sse.app.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class SubjectRoomRequirementService {
    private final SubjectRoomRequirementRepository requirements;
    private final StructureService structure;

    public SubjectRoomRequirementService(SubjectRoomRequirementRepository requirements, StructureService structure) {
        this.requirements = requirements;
        this.structure = structure;
    }

    public List<View> list(String subjectId) {
        return requirements.findAll().stream()
                .filter(item -> subjectId == null || subjectId.isBlank() || subjectId.equals(item.getSubjectId()))
                .sorted(Comparator.comparingInt(SubjectRoomRequirement::getPriority).reversed()
                        .thenComparing(SubjectRoomRequirement::getRoomType))
                .map(this::view).toList();
    }

    public List<SubjectRoomRequirement> rulesFor(String subjectId) {
        return requirements.findBySubjectId(subjectId).stream()
                .sorted(Comparator.comparingInt(SubjectRoomRequirement::getPriority).reversed()).toList();
    }

    @Transactional
    public View save(SaveRequest request) {
        Subject subject = structure.listSubjects().stream()
                .filter(item -> item.getId().equals(request.subjectId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("Môn học"));
        String roomType = request.roomType().trim().toUpperCase(Locale.ROOT);
        Instant now = Instant.now();
        SubjectRoomRequirement item = requirements.findBySubjectIdAndRoomType(subject.getId(), roomType)
                .orElseGet(() -> SubjectRoomRequirement.builder()
                        .id("srr-" + UUID.randomUUID()).subjectId(subject.getId())
                        .roomType(roomType).createdAt(now).build());
        item.setRequiredEquipment(normalizeCsv(request.requiredEquipment()));
        item.setWeeklyPeriods(request.weeklyPeriods());
        item.setMandatory(request.mandatory());
        item.setPriority(request.priority());
        item.setUpdatedAt(now);
        return view(requirements.save(item));
    }

    @Transactional
    public void delete(String id) {
        if (!requirements.existsById(id)) throw ApiException.notFound("Yêu cầu phòng học");
        requirements.deleteById(id);
    }

    private View view(SubjectRoomRequirement item) {
        Subject subject = structure.listSubjects().stream()
                .filter(candidate -> candidate.getId().equals(item.getSubjectId())).findFirst().orElse(null);
        return new View(item.getId(), item.getSubjectId(), subject == null ? "" : subject.getCode(),
                subject == null ? "Môn đã xóa" : subject.getName(), item.getRoomType(),
                item.getRequiredEquipment(), item.getWeeklyPeriods(), item.isMandatory(), item.getPriority());
    }

    private static String normalizeCsv(String value) {
        if (value == null || value.isBlank()) return null;
        return Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isBlank())
                .map(part -> part.toLowerCase(Locale.ROOT)).distinct().sorted().reduce((a, b) -> a + "," + b).orElse(null);
    }
}
