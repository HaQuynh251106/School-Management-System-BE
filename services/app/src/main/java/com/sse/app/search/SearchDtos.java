package com.sse.app.search;

import java.util.List;

public final class SearchDtos {
    private SearchDtos() {
    }

    public record SearchItem(
            String type,
            String category,
            String id,
            String title,
            String subtitle,
            String pageId
    ) {
    }

    public record SearchResponse(String query, int total, List<SearchItem> items) {
    }
}
