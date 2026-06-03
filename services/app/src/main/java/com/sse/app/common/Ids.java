package com.sse.app.common;

import java.util.UUID;

/** Sinh ID dạng "prefix-xxxxxxxx" cho bản ghi mới (PK kiểu String). */
public final class Ids {
    private Ids() {}

    public static String gen(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
