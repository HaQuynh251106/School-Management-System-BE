package com.sse.app.security;

/** Principal tối giản lấy từ JWT cho mỗi request. */
public record CurrentUser(String id, String username, String role) {
    public boolean isAdmin()   { return "ADMIN".equals(role); }
    public boolean isTeacher() { return "TEACHER".equals(role); }
    public boolean isStudent() { return "STUDENT".equals(role); }
    public boolean isParent()  { return "PARENT".equals(role); }
}
