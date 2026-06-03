package com.sse.app.security;

import com.sse.app.common.ApiException;
import org.springframework.http.HttpStatus;

/** Giữ principal của request hiện tại theo ThreadLocal (set bởi JwtAuthFilter). */
public final class CurrentUserHolder {
    private static final ThreadLocal<CurrentUser> TL = new ThreadLocal<>();

    private CurrentUserHolder() {}

    public static void set(CurrentUser u) { TL.set(u); }
    public static CurrentUser get()       { return TL.get(); }
    public static void clear()            { TL.remove(); }

    public static CurrentUser require() {
        CurrentUser u = TL.get();
        if (u == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Chưa đăng nhập");
        return u;
    }

    public static void requireRole(String... roles) {
        CurrentUser u = require();
        for (String r : roles) {
            if (r.equals(u.role())) return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "Không đủ quyền");
    }
}
