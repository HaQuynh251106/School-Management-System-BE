package com.sse.app.extracurricular;

import jakarta.validation.constraints.NotBlank;

public final class ExtracurricularDtos {
    private ExtracurricularDtos() {}

    public record CreateClubRequest(
            String id, @NotBlank String name, String description,
            Integer capacity, String schedule, Long fee) {}

    public record RegisterRequest(String studentId) {}
}
