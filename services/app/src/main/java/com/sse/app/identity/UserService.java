package com.sse.app.identity;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.IdentityDtos.*;
import com.sse.app.academic.structure.StructureService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class UserService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] TEMP_PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private final UserRepository users;
    private final ParentStudentRepository relations;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder encoder;
    private final RefreshTokenRepository refreshTokens;
    private final StructureService structure;

    public UserService(UserRepository users, ParentStudentRepository relations,
                       PasswordResetTokenRepository resetTokens, PasswordEncoder encoder,
                       RefreshTokenRepository refreshTokens, StructureService structure) {
        this.users = users;
        this.relations = relations;
        this.resetTokens = resetTokens;
        this.encoder = encoder;
        this.refreshTokens = refreshTokens;
        this.structure = structure;
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
                u.isPasswordChangeRequired(), u.getEmail(), u.getPhone(), u.getAvatarUrl(),
                u.getStudentCode(), u.getClassName(), u.getClassId(),
                u.getDateOfBirth(), u.getGender(), u.getPlaceOfBirth(),
                u.getEthnicity(), u.getNationality(), u.getAddress(),
                u.getEnrollmentDate(), u.getGuardianName(), u.getGuardianPhone(),
                u.getTeacherCode(), u.getMainSubject(), childrenIds);
    }

    /** Thông tin an toàn để dùng trong danh sách/lựa chọn, không lộ dữ liệu hồ sơ cá nhân. */
    public UserDto toSummaryDto(User u) {
        return new UserDto(
                u.getId(), u.getUsername(), u.getFullName(), u.getRole(), u.getStatus(),
                u.isPasswordChangeRequired(), null, null, u.getAvatarUrl(),
                u.getStudentCode(), u.getClassName(), u.getClassId(),
                null, null, null,
                null, null, null,
                null, null, null,
                u.getTeacherCode(), u.getMainSubject(), null);
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

    public List<String> activeUserIdsByRole(String role) {
        return users.findByRole(role).stream()
                .filter(user -> "ACTIVE".equals(user.getStatus()))
                .map(User::getId)
                .toList();
    }

    public List<String> allUserIds() {
        return users.findAll().stream().map(User::getId).toList();
    }

    public String fullNameOf(String userId) {
        return users.findById(userId).map(User::getFullName).orElse(null);
    }

    // ---------- Quản trị người dùng (A1) ----------

    public List<UserDto> list(String role, String q, String classId) {
        return filteredUsers(role, q, classId).stream()
                .map(this::toDto)
                .toList();
    }

    public int studentCountOfClass(String classId) {
        return Math.toIntExact(users.countByClassIdAndRole(classId, "STUDENT"));
    }

    @Transactional
    public UserDto linkChild(String parentId, String studentId, boolean primaryContact) {
        User parent = getById(parentId);
        User student = getById(studentId);
        if (!"PARENT".equals(parent.getRole())) throw ApiException.badRequest("Tài khoản nhận liên kết phải là phụ huynh");
        if (!"STUDENT".equals(student.getRole())) throw ApiException.badRequest("Tài khoản được liên kết phải là học sinh");
        ParentStudent relation = relations.findByParentIdAndStudentId(parentId, studentId)
                .orElseGet(() -> ParentStudent.builder().id(Ids.gen("ps")).parentId(parentId).studentId(studentId).build());
        if (primaryContact) {
            List<ParentStudent> existing = relations.findByStudentId(studentId);
            existing.forEach(item -> item.setPrimaryContact(false));
            relations.saveAll(existing);
        }
        relation.setPrimaryContact(primaryContact);
        relations.save(relation);
        return toDto(student);
    }

    @Transactional
    public void unlinkChild(String parentId, String studentId) {
        ParentStudent relation = relations.findByParentIdAndStudentId(parentId, studentId)
                .orElseThrow(() -> ApiException.notFound("Liên kết phụ huynh - học sinh"));
        relations.delete(relation);
    }

    @Transactional
    public UserDto moveStudentToClass(String studentId, String classId, String className) {
        User student = getById(studentId);
        if (!"STUDENT".equals(student.getRole())) throw ApiException.badRequest("Người dùng không phải học sinh");
        student.setClassId(classId);
        student.setClassName(className);
        User saved = users.save(student);
        structure.recordEnrollment(studentId, classId);
        return toDto(saved);
    }

    public List<UserDto> listSummaries(String role, String q, String classId) {
        return filteredUsers(role, q, classId).stream()
                .map(this::toSummaryDto)
                .toList();
    }

    private List<User> filteredUsers(String role, String q, String classId) {
        List<User> base;
        boolean filterParentsByClass = "PARENT".equalsIgnoreCase(role)
                && classId != null && !classId.isBlank();
        if (filterParentsByClass) {
            Set<String> studentIds = users.findByClassId(classId).stream()
                    .filter(user -> "STUDENT".equals(user.getRole()))
                    .map(User::getId)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> parentIds = relations.findAll().stream()
                    .filter(relation -> studentIds.contains(relation.getStudentId()))
                    .map(ParentStudent::getParentId)
                    .collect(java.util.stream.Collectors.toSet());
            base = users.findAllById(parentIds);
        } else if (role != null && !role.isBlank()) base = users.findByRole(role);
        else if (classId != null && !classId.isBlank()) base = users.findByClassId(classId);
        else base = users.findAll();

        String needle = q == null ? null : q.trim().toLowerCase();
        return base.stream()
                .filter(u -> filterParentsByClass || classId == null || classId.isBlank()
                        || classId.equals(u.getClassId()))
                .filter(u -> needle == null || needle.isEmpty() || matches(u, needle))
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
                .passwordChangeRequired(true)
                .teacherCode(r.teacherCode())
                .mainSubject(r.mainSubject())
                .studentCode(studentCode)
                .classId(r.classId())
                .className(r.className())
                .dateOfBirth(r.dateOfBirth())
                .gender(r.gender())
                .placeOfBirth(r.placeOfBirth())
                .ethnicity(r.ethnicity())
                .nationality(r.nationality())
                .address(r.address())
                .enrollmentDate(r.enrollmentDate())
                .guardianName(r.guardianName())
                .guardianPhone(r.guardianPhone())
                .createdAt(Instant.now())
                .build();
        User saved = users.save(u);
        if ("STUDENT".equals(saved.getRole()) && saved.getClassId() != null) {
            structure.recordEnrollment(saved.getId(), saved.getClassId());
        }
        return toDto(saved);
    }

    @Transactional
    public UserDto update(String id, UpdateUserRequest r) {
        User u = getById(id);
        String previousClassId = u.getClassId();
        if (r.fullName() != null)   u.setFullName(r.fullName());
        if (r.email() != null)      u.setEmail(r.email());
        if (r.phone() != null)      u.setPhone(r.phone());
        if (r.avatarUrl() != null)  u.setAvatarUrl(r.avatarUrl());
        if (r.teacherCode() != null)u.setTeacherCode(r.teacherCode());
        if (r.mainSubject() != null)u.setMainSubject(r.mainSubject());
        if (r.studentCode() != null)u.setStudentCode(r.studentCode());
        if (r.classId() != null)    u.setClassId(r.classId());
        if (r.className() != null)  u.setClassName(r.className());
        if (r.dateOfBirth() != null)u.setDateOfBirth(r.dateOfBirth());
        if (r.gender() != null)     u.setGender(r.gender());
        if (r.placeOfBirth() != null) u.setPlaceOfBirth(r.placeOfBirth());
        if (r.ethnicity() != null)  u.setEthnicity(r.ethnicity());
        if (r.nationality() != null)u.setNationality(r.nationality());
        if (r.address() != null)    u.setAddress(r.address());
        if (r.enrollmentDate() != null) u.setEnrollmentDate(r.enrollmentDate());
        if (r.guardianName() != null) u.setGuardianName(r.guardianName());
        if (r.guardianPhone() != null) u.setGuardianPhone(r.guardianPhone());
        User saved = users.save(u);
        if ("STUDENT".equals(saved.getRole()) && saved.getClassId() != null
                && !Objects.equals(previousClassId, saved.getClassId())) {
            structure.recordEnrollment(saved.getId(), saved.getClassId());
        }
        return toDto(saved);
    }

    @Transactional
    public UserDto setStatus(String id, String status) {
        User u = getById(id);
        if (!Objects.equals(u.getStatus(), status)) u.setTokenVersion(u.getTokenVersion() + 1);
        u.setStatus(status);
        return toDto(users.save(u));
    }

    /** A1: admin reset mật khẩu; trả lại mật khẩu mới (sinh ngẫu nhiên nếu không truyền). */
    @Transactional
    public String adminResetPassword(String id, String newPassword) {
        User u = getById(id);
        String pwd = (newPassword == null || newPassword.isBlank()) ? temporaryPassword() : newPassword;
        if (pwd.length() < 10 || pwd.length() > 128) {
            throw ApiException.badRequest("Mật khẩu tạm phải có từ 10 đến 128 ký tự");
        }
        u.setPasswordHash(encoder.encode(pwd));
        u.setPasswordChangeRequired(true);
        u.setTokenVersion(u.getTokenVersion() + 1);
        users.save(u);
        revokeRefreshTokens(u.getId());
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

    @Transactional
    public UserDto updateMyProfile(String id, UpdateMyProfileRequest request) {
        User user = getById(id);
        if (request.email() != null) user.setEmail(cleanProfileValue(request.email()));
        if (request.phone() != null) user.setPhone(cleanProfileValue(request.phone()));
        if (request.avatarUrl() != null) user.setAvatarUrl(cleanProfileValue(request.avatarUrl()));
        if (request.address() != null) user.setAddress(cleanProfileValue(request.address()));
        if ("STUDENT".equals(user.getRole())) {
            if (request.guardianName() != null) user.setGuardianName(cleanProfileValue(request.guardianName()));
            if (request.guardianPhone() != null) user.setGuardianPhone(cleanProfileValue(request.guardianPhone()));
        }
        return toDto(users.save(user));
    }

    private String cleanProfileValue(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
        u.setPasswordChangeRequired(false);
        u.setTokenVersion(u.getTokenVersion() + 1);
        users.save(u);
        revokeRefreshTokens(u.getId());
        t.setUsedAt(Instant.now());
        resetTokens.save(t);
    }

    @Transactional
    public void changePassword(String userId, String currentPassword, String newPassword) {
        User user = getById(userId);
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("Mật khẩu hiện tại không chính xác");
        }
        if (encoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("Mật khẩu mới phải khác mật khẩu hiện tại");
        }
        user.setPasswordHash(encoder.encode(newPassword));
        user.setPasswordChangeRequired(false);
        user.setTokenVersion(user.getTokenVersion() + 1);
        users.save(user);
        revokeRefreshTokens(userId);
    }

    @Transactional
    public void requirePasswordChange(String userId) {
        User user = getById(userId);
        user.setPasswordChangeRequired(true);
        user.setTokenVersion(user.getTokenVersion() + 1);
        users.save(user);
    }

    private String temporaryPassword() {
        StringBuilder value = new StringBuilder("Sse@");
        for (int i = 0; i < 12; i++) {
            value.append(TEMP_PASSWORD_ALPHABET[SECURE_RANDOM.nextInt(TEMP_PASSWORD_ALPHABET.length)]);
        }
        return value.toString();
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

    private void revokeRefreshTokens(String userId) {
        Instant now = Instant.now();
        List<RefreshToken> active = refreshTokens.findByUserId(userId).stream()
                .filter(token -> token.getRevokedAt() == null).toList();
        active.forEach(token -> token.setRevokedAt(now));
        refreshTokens.saveAll(active);
    }
}
