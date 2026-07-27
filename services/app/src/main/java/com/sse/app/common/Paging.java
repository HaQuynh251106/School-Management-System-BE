package com.sse.app.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Centralized safety limits for every public paginated endpoint. */
public final class Paging {
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private Paging() {
    }

    public static PageRequest request(int page, int size, Sort sort) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_SIZE, Math.max(1, size <= 0 ? DEFAULT_SIZE : size));
        return PageRequest.of(safePage, safeSize, sort == null ? Sort.unsorted() : sort);
    }
}
