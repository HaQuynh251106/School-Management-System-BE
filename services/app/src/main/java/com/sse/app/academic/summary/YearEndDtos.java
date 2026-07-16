package com.sse.app.academic.summary;

import jakarta.validation.constraints.Pattern;

public final class YearEndDtos {
    private YearEndDtos() {}

    public record ConductRequest(
            @Pattern(regexp = "GOOD|FAIR|AVERAGE|WEAK", message = "Hạnh kiểm không hợp lệ")
            String conductGrade) {}
}
