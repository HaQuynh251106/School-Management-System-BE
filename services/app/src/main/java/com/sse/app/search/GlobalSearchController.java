package com.sse.app.search;

import com.sse.app.search.SearchDtos.SearchResponse;
import com.sse.app.security.CurrentUserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/search")
public class GlobalSearchController {
    private final GlobalSearchService search;

    public GlobalSearchController(GlobalSearchService search) {
        this.search = search;
    }

    @GetMapping
    public SearchResponse search(@RequestParam String q,
                                 @RequestParam(defaultValue = "20") int limit) {
        return search.search(CurrentUserHolder.require(), q, limit);
    }
}
