package com.sse.app.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;

/**
 * Stable pagination contract for every frontend client.
 * Do not expose Spring's Page implementation directly because its JSON shape may change.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        Map<String, Long> summary
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return from(page, Map.of());
    }

    public static <T> PageResponse<T> from(Page<T> page, Map<String, Long> summary) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                summary == null ? Map.of() : Map.copyOf(summary)
        );
    }
}
