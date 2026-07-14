package com.sse.app.identity;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.IdentityDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class UserService {

    private final UserRepository users;
    private final ParentStudentRepository relations;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder encoder;

    public UserService(UserRepository users, ParentStudentRepository relations,
                       PasswordResetTokenRepository resetTokens, PasswordEncoder encoder) {
        this.users = users;
        this.relations = relations;
        this.resetTokens = resetTokens;
        this.encoder = encoder;
    }

    // ---------- Tra cứu ----------

    public User getById(String id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("Người dùng"));
    }

    public Optional<User> findByUsername(String username) {
        return users.findByUsername(username);
    }

    public UserDto toDto(User u) {
        List<String> childrenIds = null;
        if ("PARENT".equals(u.getRole())) {
            childrenIds = relations.findByParentId(u.getId()).stream()
                    .map(ParentStudent::getStudentId).toList();
        }
        return new UserDto(
                u.getId(), u.getUsername(), u.getFullName(), u.getRole(), u.getStatus(),
                u.getEmail(), u.getPhone(), u.getAvatarUrl(),
                u.getStudentCode(), u.getClassName(), u.getClassId(),
                u.getTeacherCode(), u.getMainSubject(), childrenIds);
    }

    public UserDto dtoById(String id) {
        return toDto(getById(id));
    }

    // ---------- Đăng nhập / xác thực ----------

    public User authenticate(String username, String rawPassword) {
        User u = users.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Sai tên đăng nhập hoặc mật khẩu"));
        if (!"ACTIVE".equals(u.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Tài khoản bị khóa");
        }
        if (!encoder.matches(rawPassword, u.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sai tên đăng nhập hoặc mật khẩu");
        }
        return u;
    }

    // ---------- Quan hệ phụ huynh - học sinh (D1) ----------

    public List<UserDto> childrenOf(String parentId) {
        return relations.findByParentId(parentId).stream()
                .map(ParentStudent::getStudentId)
                .flatMap(sid -> users.findById(sid).stream())
                .map(this::toDto)
                .toList();
    }

    /** D1/D2: chặn PH truy cập học sinh không phải con mình. */
    public void assertParentOf(String parentId, String studentId) {
        if (!relations.existsByParentIdAndStudentId(parentId, studentId)) {
            throw ApiException.forbidden("Không có quyền truy cập học sinh này");
        }
    }

    /** D2/2.5: tìm phụ huynh của một học sinh (để gửi cảnh báo vắng/điểm/hóa đơn). */
    public List<String> parentIdsOf(String studentId) {
        return relations.findByStudentId(studentId).stream()
                .map(ParentStudent::getParentId).toList();
    }

    public List<String> userIdsByRole(String role) {
        return users.findByRole(role).stream().map(User::getId).toList();
    }

    public List<String> allUserIds() {
        return users.findAll().stream().map(User::getId).toList();
    }

    public String fullNameOf(String userId) {
        return users.findById(userId).map(User::getFullName).orElse(null);
    }

    // ---------- Quản trị người dùng (A1) ----------

    public List<UserDto> list(String role, String q, String classId) {
        List<User> base;
        if (role != null && !role.isBlank()) base = users.findByRole(role);
        else if (classId != null && !classId.isBlank()) base = users.findByClassId(classId);
        else base = users.findAll();

        String needle = q == null ? null : q.trim().toLowerCase();
        return base.stream()
                .filter(u -> classId == null || classId.isBlank() || classId.equals(u.getClassId()))
                .filter(u -> needle == null || needle.isEmpty() || matches(u, needle))
                .map(this::toDto)
                .toList();
    }

    private boolean matches(User u, String needle) {
        return contains(u.getFullName(), needle) || contains(u.getUsername(), needle)
                || contains(u.getEmail(), needle) || contains(u.getStudentCode(), needle)
                || contains(u.getTeacherCode(), needle);
    }

    private boolean contains(String s, String needle) {
        return s != null && s.toLowerCase().contains(needle);
    }

    @Transactional
    public UserDto create(CreateUserRequest r) {
        if (users.existsByUsername(r.username())) {
            throw ApiException.conflict("Tên đăng nhập đã tồn tại");
        }
        String id = (r.id() == null || r.id().isBlank()) ? Ids.gen("u") : r.id();
        // Học sinh không nhập mã thủ công — hệ thống tự sinh mã HS.
        String studentCode = r.studentCode();
        if ("STUDENT".equals(r.role()) && (studentCode == null || studentCode.isBlank())) {
            studentCode = "HS2025" + String.format("%03d", users.findByRole("STUDENT").size() + 1);
        }
        User u = User.builder()
                .id(id)
                .username(r.username())
                .passwordHash(encoder.encode(r.password()))
                .fullName(r.fullName())
                .role(r.role())
                .email(r.email())
                .phone(r.phone())
                .avatarUrl(r.avatarUrl())
                .status("ACTIVE")
                .teacherCode(r.teacherCode())
                .mainSubject(r.mainSubject())
                .studentCode(studentCode)
                .classId(r.classId())
                .className(r.className())
                .createdAt(Instant.now())
                .build();
        return toDto(users.save(u));
    }

    @Transactional
    public UserDto update(String id, UpdateUserRequest r) {
        User u = getById(id);
        if (r.fullName() != null)   u.setFullName(r.fullName());
        if (r.email() != null)      u.setEmail(r.email());
        if (r.phone() != null)      u.setPhone(r.phone());
        if (r.avatarUrl() != null)  u.setAvatarUrl(r.avatarUrl());
        if (r.teacherCode() != null)u.setTeacherCode(r.teacherCode());
        if (r.mainSubject() != null)u.setMainSubject(r.mainSubject());
        if (r.studentCode() != null)u.setStudentCode(r.studentCode());
        if (r.classId() != null)    u.setClassId(r.classId());
        if (r.className() != null)  u.setClassName(r.className());
        return toDto(users.save(u));
    }

    @Transactional
    public UserDto setStatus(String id, String status) {
        User u = getById(id);
        u.setStatus(status);
        return toDto(users.save(u));
    }

    /** A1: admin reset mật khẩu; trả lại mật khẩu mới (sinh ngẫu nhiên nếu không truyền). */
    @Transactional
    public String adminResetPassword(String id, String newPassword) {
        User u = getById(id);
        String pwd = (newPassword == null || newPassword.isBlank())
                ? "Sse@" + (100000 + new Random().nextInt(900000))
                : newPassword;
        u.setPasswordHash(encoder.encode(pwd));
        users.save(u);
        return pwd;
    }

    // ---------- Quên mật khẩu (A1 - flowchart 2.2) ----------

    /** Sinh reset token; trả token thô (DEV) hoặc null nếu không tìm thấy user (chống enumeration). */
    @Transactional
    public PasswordResetIssue requestPasswordReset(String email, String username) {
        Optional<User> found = Optional.empty();
        if (email != null && !email.isBlank())       found = users.findByEmail(email);
        if (found.isEmpty() && username != null && !username.isBlank())
            found = users.findByUsername(username);
        if (found.isEmpty()) return null;

        String raw = UUID.randomUUID().toString().replace("-", "");
        resetTokens.save(PasswordResetToken.builder()
                .id(Ids.gen("prt"))
                .userId(found.get().getId())
                .tokenHash(sha256(raw))
                .expiresAt(Instant.now().plus(30, ChronoUnit.MINUTES))
                .build());
        return new PasswordResetIssue(raw, found.get().getEmail());
    }

    public record PasswordResetIssue(String token, String email) {}

    @Transactional
    public void confirmPasswordReset(String rawToken, String newPassword) {
        PasswordResetToken t = resetTokens.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> ApiException.badRequest("Token không hợp lệ"));
        if (t.getUsedAt() != null) throw ApiException.badRequest("Token đã được dùng");
        if (t.getExpiresAt().isBefore(Instant.now())) throw ApiException.badRequest("Token đã hết hạn");

        User u = getById(t.getUserId());
        u.setPasswordHash(encoder.encode(newPassword));
        users.save(u);
        t.setUsedAt(Instant.now());
        resetTokens.save(t);
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
