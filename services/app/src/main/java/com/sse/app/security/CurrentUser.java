package com.sse.app.security;

import java.util.Set;

/** Principal tối giản lấy từ JWT cho mỗi request. */
public record CurrentUser(
        String id,
        String username,
        String role,
        Set<String> permissions,
        boolean passwordChangeRequired,
        int sessionVersion,
        String sessionId) {
    public CurrentUser(String id, String username, String role) {
        this(id, username, role, Set.of(), false, 0, null);
    }

    public boolean isAdmin()   { return "ADMIN".equals(role); }
    public boolean isTeacher() { return "TEACHER".equals(role); }
    public boolean isStudent() { return "STUDENT".equals(role); }
    public boolean isParent()  { return "PARENT".equals(role); }
    public boolean hasPermission(String permission) {
        return permissions != null && permissions.contains(permission);
    }
}
