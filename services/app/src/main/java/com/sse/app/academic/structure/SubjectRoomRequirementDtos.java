package com.sse.app.academic.structure;

import jakarta.validation.constraints.*;

public final class SubjectRoomRequirementDtos {
    private SubjectRoomRequirementDtos() {}

    public record SaveRequest(
            @NotBlank String subjectId,
            @NotBlank @Pattern(regexp = "GENERAL|LAB|COMPUTER|LANGUAGE|SPORT|ART|LIBRARY|MULTIPURPOSE|OTHER") String roomType,
            String requiredEquipment,
            @Min(0) @Max(20) int weeklyPeriods,
            boolean mandatory,
            @Min(0) @Max(100) int priority
    ) {}

    public record View(
            String id, String subjectId, String subjectCode, String subjectName,
            String roomType, String requiredEquipment, int weeklyPeriods,
            boolean mandatory, int priority
    ) {}
}
