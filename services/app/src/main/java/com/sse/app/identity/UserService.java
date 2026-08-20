package com.sse.app.identity;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.IdentityDtos.*;
import com.sse.app.report.AcademicEnrollmentService;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${sse.web.base-url:http://127.0.0.1:5173}")
    private String webBaseUrl;

    private final UserRepository users;
    private final ParentStudentRepository relations;
    private final PasswordResetTokenRepository resetTokens;
    private final RefreshTokenRepository refreshTokens;
    private final LoginHistoryRepository loginHistory;
    private final UserDeviceRepository devices;
    private final RbacService rbac;
    private final PasswordEncoder encoder;
    private final DomainEventPublisher events;
    private final AcademicEnrollmentService academicEnrollment;

    public UserService(UserRepository users, ParentStudentRepository relations,
                       PasswordResetTokenRepository resetTokens, RefreshTokenRepository refreshTokens,
                       LoginHistoryRepository loginHistory, UserDeviceRepository devices,
                       RbacService rbac, PasswordEncoder encoder, DomainEventPublisher events,
                       AcademicEnrollmentService academicEnrollment) {
        this.users = users;
        this.relations = relations;
        this.resetTokens = resetTokens;
        this.refreshTokens = refreshTokens;
        this.loginHistory = loginHistory;
        this.devices = devices;
        this.rbac = rbac;
        this.encoder = encoder;
        this.events = events;
        this.academicEnrollment = academicEnrollment;
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
                u.getTeacherCode(), u.getMainSubject(), childrenIds,
                u.isPasswordChangeRequired(), u.getPasswordChangedAt(),
                u.getDeletedAt(), u.getDeleteReason(), u.getRestoredAt(),
                new ArrayList<>(rbac.permissionsFor(u.getId())),
                u.getDateOfBirth(), u.getGender(), u.getPlaceOfBirth(),
                u.getEthnicity(), u.getNationality(), u.getAddress(),
                u.getEnrollmentDate(), u.getGuardianName(), u.getGuardianPhone());
    }

    public UserDto dtoById(String id) {
        return toDto(getById(id));
    }

    // ---------- Đăng nhập / xác thực ----------

    @Transactional
    public User authenticate(String username, String rawPassword, String ipAddress, String userAgent) {
        String identifier = username == null ? "" : username.trim();
        long recentFailures = loginHistory
                .countByUsernameAndIpAddressAndSuccessFalseAndCreatedAtAfter(
                        identifier, ipAddress,
                        Instant.now().minus(15, ChronoUnit.MINUTES));
        if (recentFailures >= 5) {
            recordLogin(null, identifier, ipAddress, userAgent,
                    false, "RATE_LIMITED");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Đăng nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút");
        }
        Optional<User> found = users.findByUsername(identifier);
        if (found.isEmpty() && !identifier.isBlank()) {
            found = users.findByLoginIdentifier(identifier).stream().findFirst();
        }
        if (found.isEmpty()) {
            recordLogin(null, identifier, ipAddress, userAgent, false, "USER_NOT_FOUND");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sai tên đăng nhập hoặc mật khẩu");
        }

        User u = found.get();
        if (!"ACTIVE".equals(u.getStatus())) {
            recordLogin(u.getId(), identifier, ipAddress, userAgent, false, "USER_" + u.getStatus());
            throw new ApiException(HttpStatus.FORBIDDEN, "Tài khoản bị khóa");
        }
        if (!encoder.matches(rawPassword, u.getPasswordHash())) {
            recordLogin(u.getId(), identifier, ipAddress, userAgent, false, "BAD_PASSWORD");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sai tên đăng nhập hoặc mật khẩu");
        }

        recordLogin(u.getId(), identifier, ipAddress, userAgent, true, null);
        events.publish("identity.user.login", u.getId(), "user", u.getId(),
                Map.of("username", u.getUsername(), "role", u.getRole()));
        return u;
    }

    @Transactional
    public void storeRefreshToken(String sessionId, String userId, String rawToken,
                                  long ttlSeconds, String ipAddress, String userAgent,
                                  String deviceId, int sessionVersion) {
        Instant now = Instant.now();
        refreshTokens.save(RefreshToken.builder()
                .id(sessionId)
                .userId(userId)
                .tokenHash(sha256(rawToken))
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .deviceId(deviceId)
                .sessionVersion(sessionVersion)
                .lastSeenAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .createdAt(now)
                .build());
    }

    @Transactional
    public SessionRotation verifyAndRotateRefreshToken(String rawToken, int claimedSessionVersion) {
        RefreshToken token = refreshTokens.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
        if (token.getRevokedAt() != null || token.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        User user = getById(token.getUserId());
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Tài khoản bị khóa");
        }
        if (token.getSessionVersion() != user.getSessionVersion()
                || claimedSessionVersion != user.getSessionVersion()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Session has been revoked");
        }
        token.setRevokedAt(Instant.now());
        token.setRevokedReason("ROTATED");
        token.setLastSeenAt(Instant.now());
        refreshTokens.save(token);
        return new SessionRotation(user, token.getDeviceId());
    }

    @Transactional
    public void revokeRefreshToken(String rawToken, String actorId, String reason) {
        if (rawToken == null || rawToken.isBlank()) return;
        refreshTokens.findByTokenHash(sha256(rawToken)).ifPresent(t -> {
            if (t.getRevokedAt() == null) {
                t.setRevokedAt(Instant.now());
                t.setRevokedBy(actorId);
                t.setRevokedReason(reason);
                refreshTokens.save(t);
            }
        });
    }

    @Transactional
    public int revokeAllRefreshTokens(String userId, String actorId, String reason) {
        Instant now = Instant.now();
        List<RefreshToken> active = refreshTokens.findByUserIdAndRevokedAtIsNull(userId);
        active.forEach(t -> {
            t.setRevokedAt(now);
            t.setRevokedBy(actorId);
            t.setRevokedReason(reason);
        });
        refreshTokens.saveAll(active);
        return active.size();
    }

    @Transactional
    public void touchSession(String userId, String sessionId) {
        refreshTokens.findByIdAndUserIdAndRevokedAtIsNull(sessionId, userId).ifPresent(token -> {
            Instant now = Instant.now();
            if (token.getLastSeenAt() == null
                    || token.getLastSeenAt().isBefore(now.minus(5, ChronoUnit.MINUTES))) {
                token.setLastSeenAt(now);
                refreshTokens.save(token);
            }
        });
    }

    private void recordLogin(String userId, String username, String ipAddress, String userAgent,
                             boolean success, String failureReason) {
        loginHistory.save(LoginHistory.builder()
                .id(Ids.gen("lh"))
                .userId(userId)
                .username(username)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .success(success)
                .failureReason(failureReason)
                .createdAt(Instant.now())
                .build());
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
        return list(role, q, classId, null, false);
    }

    public List<UserDto> list(String role, String q, String classId,
                              String status, boolean includeDeleted) {
        List<User> base;
        if (role != null && !role.isBlank()) base = users.findByRole(role);
        else if (classId != null && !classId.isBlank()) base = users.findByClassId(classId);
        else base = users.findAll();

        String needle = q == null ? null : q.trim().toLowerCase();
        return base.stream()
                .filter(u -> includeDeleted || !"DELETED".equals(u.getStatus()))
                .filter(u -> status == null || status.isBlank() || status.equals(u.getStatus()))
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
        return create(r, null);
    }

    @Transactional
    public UserDto create(CreateUserRequest r, String actorId) {
        if (users.existsByUsername(r.username())) {
            throw ApiException.conflict("Tên đăng nhập đã tồn tại");
        }
        validateRole(r.role());
        validatePassword(r.password());
        String initialStatus = r.status() == null || r.status().isBlank()
                ? "ACTIVE" : r.status().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "PENDING").contains(initialStatus)) {
            throw ApiException.badRequest("Trang thai tao moi phai la ACTIVE hoac PENDING");
        }
        String id = (r.id() == null || r.id().isBlank()) ? Ids.gen("u") : r.id();
        // Học sinh không nhập mã thủ công — hệ thống tự sinh mã HS.
        String studentCode = r.studentCode();
        if ("STUDENT".equals(r.role()) && (studentCode == null || studentCode.isBlank())) {
            studentCode = "HS2025" + String.format("%03d", users.findByRole("STUDENT").size() + 1);
        }
        Instant now = Instant.now();
        User u = User.builder()
                .id(id)
                .username(r.username())
                .passwordHash(encoder.encode(r.password()))
                .fullName(r.fullName())
                .role(r.role())
                .email(r.email())
                .phone(r.phone())
                .avatarUrl(r.avatarUrl())
                .status(initialStatus)
                .passwordChangeRequired(true)
                .sessionVersion(0)
                .teacherCode(r.teacherCode())
                .mainSubject(r.mainSubject())
                .studentCode(studentCode)
                .classId(r.classId())
                .className(r.className())
                .dateOfBirth(r.dateOfBirth())
                .gender(normalizeGender(r.gender()))
                .placeOfBirth(blankToNull(r.placeOfBirth()))
                .ethnicity(blankToNull(r.ethnicity()))
                .nationality(blankToNull(r.nationality()))
                .address(blankToNull(r.address()))
                .enrollmentDate(r.enrollmentDate())
                .guardianName(blankToNull(r.guardianName()))
                .guardianPhone(blankToNull(r.guardianPhone()))
                .createdAt(now)
                .updatedAt(now)
                .build();
        User saved = users.save(u);
        rbac.assignPrimaryRole(saved.getId(), saved.getRole(), actorId);
        if ("STUDENT".equals(saved.getRole())) {
            academicEnrollment.assignStudentCurrentClass(saved.getId(), saved.getClassId(), actorId);
        }
        return toDto(saved);
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
        if (r.dateOfBirth() != null) u.setDateOfBirth(r.dateOfBirth());
        if (r.gender() != null) u.setGender(normalizeGender(r.gender()));
        if (r.placeOfBirth() != null) u.setPlaceOfBirth(blankToNull(r.placeOfBirth()));
        if (r.ethnicity() != null) u.setEthnicity(blankToNull(r.ethnicity()));
        if (r.nationality() != null) u.setNationality(blankToNull(r.nationality()));
        if (r.address() != null) u.setAddress(blankToNull(r.address()));
        if (r.enrollmentDate() != null) u.setEnrollmentDate(r.enrollmentDate());
        if (r.guardianName() != null) u.setGuardianName(blankToNull(r.guardianName()));
        if (r.guardianPhone() != null) u.setGuardianPhone(blankToNull(r.guardianPhone()));
        u.setUpdatedAt(Instant.now());
        User saved = users.save(u);
        if ("STUDENT".equals(saved.getRole()) && r.classId() != null) {
            academicEnrollment.assignStudentCurrentClass(saved.getId(), saved.getClassId(), null);
        }
        return toDto(saved);
    }

    private String normalizeGender(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("MALE", "FEMALE", "OTHER").contains(normalized)) {
            throw ApiException.badRequest("Giới tính chỉ nhận Nam, Nữ hoặc Khác");
        }
        return normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Transactional
    public UserDto setStatus(String id, String status) {
        return changeStatus(id, status, null);
    }

    @Transactional
    public UserDto changeStatus(String id, String status, String actorId) {
        User u = getById(id);
        if ("DELETED".equals(u.getStatus())) {
            throw ApiException.badRequest("Hay khoi phuc tai khoan truoc");
        }
        String next = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "LOCKED", "PENDING").contains(next)) {
            throw ApiException.badRequest("Trang thai khong hop le");
        }
        if ("ADMIN".equals(u.getRole()) && !"ACTIVE".equals(next)
                && users.countByRoleAndStatusAndDeletedAtIsNull("ADMIN", "ACTIVE") <= 1) {
            throw ApiException.badRequest("Khong the khoa Admin hoat dong cuoi cung");
        }
        if (!"ACTIVE".equals(next)) {
            invalidateSessions(u, actorId, "STATUS_" + next);
        }
        u.setStatus(next);
        u.setUpdatedAt(Instant.now());
        return toDto(users.save(u));
    }

    /** A1: admin reset mật khẩu; trả lại mật khẩu mới (sinh ngẫu nhiên nếu không truyền). */
    @Transactional
    public PasswordResetResult adminResetPassword(String id, String newPassword, String actorId) {
        User u = getById(id);
        if ("DELETED".equals(u.getStatus())) {
            throw ApiException.badRequest("Khong the reset tai khoan da xoa");
        }
        String pwd = (newPassword == null || newPassword.isBlank())
                ? temporaryPassword()
                : newPassword;
        validatePassword(pwd);
        u.setPasswordHash(encoder.encode(pwd));
        u.setPasswordChangeRequired(true);
        u.setPasswordChangedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        u.setSessionVersion(u.getSessionVersion() + 1);
        users.save(u);
        int revoked = revokeAllRefreshTokens(u.getId(), actorId, "ADMIN_PASSWORD_RESET");
        return new PasswordResetResult(true, pwd, true, revoked);
    }

    @Transactional
    public void changeOwnPassword(String userId, String currentPassword, String newPassword) {
        User user = getById(userId);
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("Mat khau hien tai khong dung");
        }
        if (encoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("Mat khau moi phai khac mat khau hien tai");
        }
        validatePassword(newPassword);
        user.setPasswordHash(encoder.encode(newPassword));
        user.setPasswordChangeRequired(false);
        user.setPasswordChangedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setSessionVersion(user.getSessionVersion() + 1);
        users.save(user);
        revokeAllRefreshTokens(userId, userId, "PASSWORD_CHANGED");
    }

    @Transactional
    public UserDto softDelete(String id, String actorId, String reason) {
        if (id.equals(actorId)) {
            throw ApiException.badRequest("Admin khong the tu xoa tai khoan dang dang nhap");
        }
        User user = getById(id);
        if ("DELETED".equals(user.getStatus())) {
            return toDto(user);
        }
        if ("ADMIN".equals(user.getRole())
                && users.countByRoleAndStatusAndDeletedAtIsNull("ADMIN", "ACTIVE") <= 1) {
            throw ApiException.badRequest("Khong the xoa Admin hoat dong cuoi cung");
        }
        invalidateSessions(user, actorId, "USER_DELETED");
        deactivateAllDevices(user.getId(), actorId, "USER_DELETED");
        Instant now = Instant.now();
        user.setStatus("DELETED");
        user.setDeletedAt(now);
        user.setDeletedBy(actorId);
        user.setDeleteReason(requireReason(reason));
        user.setUpdatedAt(now);
        return toDto(users.save(user));
    }

    @Transactional
    public UserDto restore(String id, String actorId, String status, String reason) {
        User user = getById(id);
        if (!"DELETED".equals(user.getStatus())) {
            throw ApiException.badRequest("Tai khoan chua bi xoa");
        }
        String next = status == null || status.isBlank()
                ? "PENDING" : status.toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "PENDING").contains(next)) {
            throw ApiException.badRequest("Trang thai khoi phuc phai la ACTIVE hoac PENDING");
        }
        requireReason(reason);
        Instant now = Instant.now();
        user.setStatus(next);
        user.setDeletedAt(null);
        user.setDeletedBy(null);
        user.setDeleteReason(null);
        user.setRestoredAt(now);
        user.setRestoredBy(actorId);
        user.setPasswordChangeRequired(true);
        user.setUpdatedAt(now);
        return toDto(users.save(user));
    }

    // ---------- Quên mật khẩu (A1 - flowchart 2.2) ----------

    /** Sinh reset token; trả token thô (DEV) hoặc null nếu không tìm thấy user (chống enumeration). */
    @Transactional
    public String requestPasswordReset(String email, String username) {
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
        String baseUrl = webBaseUrl == null || webBaseUrl.isBlank()
                ? "http://127.0.0.1:5173" : webBaseUrl;
        String resetUrl = baseUrl.replaceAll("/+$", "") + "/?token="
                + java.net.URLEncoder.encode(raw, StandardCharsets.UTF_8);
        events.publish("identity.password.reset_requested", found.get().getId(), "user", found.get().getId(),
                Map.of(
                        "username", found.get().getUsername(),
                        "email", Optional.ofNullable(found.get().getEmail()).orElse(""),
                        "resetUrl", resetUrl,
                        "expiresInMinutes", 30));
        return raw;
    }

    @Transactional
    public void confirmPasswordReset(String rawToken, String newPassword) {
        validatePassword(newPassword);
        PasswordResetToken t = resetTokens.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> ApiException.badRequest("Token không hợp lệ"));
        if (t.getUsedAt() != null) throw ApiException.badRequest("Token đã được dùng");
        if (t.getExpiresAt().isBefore(Instant.now())) throw ApiException.badRequest("Token đã hết hạn");

        User u = getById(t.getUserId());
        u.setPasswordHash(encoder.encode(newPassword));
        u.setPasswordChangeRequired(false);
        u.setPasswordChangedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        u.setSessionVersion(u.getSessionVersion() + 1);
        users.save(u);
        t.setUsedAt(Instant.now());
        resetTokens.save(t);
        revokeAllRefreshTokens(u.getId(), u.getId(), "PASSWORD_RESET_COMPLETED");
        events.publish("identity.password.reset_completed", u.getId(), "user", u.getId(),
                Map.of("username", u.getUsername()));
    }

    @Transactional
    public UserDevice registerDevice(String userId, RegisterDeviceRequest r) {
        return registerDevice(userId, r.deviceToken(), r.platform(), r.deviceName(), null, null);
    }

    @Transactional
    public UserDevice registerDevice(String userId, String deviceToken, String platform,
                                     String deviceName, String ipAddress, String userAgent) {
        if (deviceToken == null || deviceToken.isBlank()) return null;
        UserDevice device = devices.findByUserIdAndDeviceToken(userId, deviceToken)
                .orElseGet(() -> UserDevice.builder()
                        .id(Ids.gen("dev"))
                        .userId(userId)
                        .deviceToken(deviceToken)
                        .createdAt(Instant.now())
                        .build());
        device.setPlatform(platform == null || platform.isBlank()
                ? "WEB" : platform.toUpperCase(Locale.ROOT));
        device.setDeviceName(deviceName);
        device.setActive(true);
        device.setLastSeenAt(Instant.now());
        device.setLastIpAddress(ipAddress);
        device.setLastUserAgent(userAgent);
        device.setDeactivatedAt(null);
        device.setDeactivatedBy(null);
        device.setDeactivationReason(null);
        return devices.save(device);
    }

    public List<UserDevice> activeDevices(String userId) {
        return devices.findByUserIdAndActiveIsTrue(userId);
    }

    public List<SessionResponse> activeSessions(String userId) {
        return activeSessions(userId, null);
    }

    public List<SessionResponse> activeSessions(String userId, String currentSessionId) {
        Instant now = Instant.now();
        Map<String, UserDevice> deviceMap = devices.findByUserIdOrderByLastSeenAtDesc(userId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        UserDevice::getId, device -> device, (left, right) -> left));
        return refreshTokens.findByUserIdAndRevokedAtIsNull(userId).stream()
                .filter(token -> token.getExpiresAt().isAfter(now))
                .map(token -> {
                    UserDevice device = deviceMap.get(token.getDeviceId());
                    return new SessionResponse(
                            token.getId(), token.getIpAddress(), token.getUserAgent(),
                            token.getDeviceId(),
                            device == null ? null : device.getDeviceName(),
                            device == null ? null : device.getPlatform(),
                            token.getCreatedAt(), token.getLastSeenAt(), token.getExpiresAt(),
                            true, token.getId().equals(currentSessionId));
                })
                .sorted(Comparator.comparing(
                        SessionResponse::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional
    public void revokeSession(String userId, String sessionId) {
        revokeSession(userId, sessionId, userId, "USER_REVOKED");
    }

    @Transactional
    public void revokeSession(String userId, String sessionId, String actorId, String reason) {
        RefreshToken token = refreshTokens.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Phiên đăng nhập"));
        if (!userId.equals(token.getUserId())) {
            throw ApiException.forbidden("Không phải phiên đăng nhập của bạn");
        }
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(Instant.now());
            token.setRevokedBy(actorId);
            token.setRevokedReason(reason);
            refreshTokens.save(token);
        }
    }

    public List<UserDevice> devices(String userId) {
        return devices.findByUserIdAndActiveIsTrue(userId);
    }

    public List<UserDevice> devices(String userId, boolean includeInactive) {
        return includeInactive
                ? devices.findByUserIdOrderByLastSeenAtDesc(userId)
                : devices.findByUserIdAndActiveIsTrue(userId);
    }

    @Transactional
    public UserDevice deactivateDevice(String userId, String deviceId) {
        return deactivateDevice(userId, deviceId, userId, "USER_DEACTIVATED");
    }

    @Transactional
    public UserDevice deactivateDevice(String userId, String deviceId,
                                       String actorId, String reason) {
        UserDevice device = devices.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("Thiết bị"));
        if (!userId.equals(device.getUserId())) {
            throw ApiException.forbidden("Không phải thiết bị của bạn");
        }
        device.setActive(false);
        device.setLastSeenAt(Instant.now());
        device.setDeactivatedAt(Instant.now());
        device.setDeactivatedBy(actorId);
        device.setDeactivationReason(reason);
        revokeDeviceSessions(userId, deviceId, actorId, reason);
        return devices.save(device);
    }

    public List<LoginHistory> loginHistory(String userId) {
        getById(userId);
        return loginHistory.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }

    public boolean canReadUser(String requesterId, String targetId, boolean hasUserReadPermission) {
        if (requesterId.equals(targetId) || hasUserReadPermission) return true;
        return relations.existsByParentIdAndStudentId(requesterId, targetId);
    }

    private void invalidateSessions(User user, String actorId, String reason) {
        user.setSessionVersion(user.getSessionVersion() + 1);
        revokeAllRefreshTokens(user.getId(), actorId, reason);
    }

    private void deactivateAllDevices(String userId, String actorId, String reason) {
        Instant now = Instant.now();
        List<UserDevice> active = devices.findByUserIdAndActiveIsTrue(userId);
        active.forEach(device -> {
            device.setActive(false);
            device.setDeactivatedAt(now);
            device.setDeactivatedBy(actorId);
            device.setDeactivationReason(reason);
        });
        devices.saveAll(active);
    }

    private void revokeDeviceSessions(String userId, String deviceId, String actorId, String reason) {
        Instant now = Instant.now();
        List<RefreshToken> active = refreshTokens.findByUserIdAndRevokedAtIsNull(userId);
        active.stream()
                .filter(token -> deviceId.equals(token.getDeviceId()))
                .forEach(token -> {
                    token.setRevokedAt(now);
                    token.setRevokedBy(actorId);
                    token.setRevokedReason(reason);
                });
        refreshTokens.saveAll(active);
    }

    private String requireReason(String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw ApiException.badRequest("Ly do phai co it nhat 5 ky tu");
        }
        return reason.trim();
    }

    private void validateRole(String role) {
        if (role == null || !Set.of("ADMIN", "TEACHER", "STUDENT", "PARENT").contains(role)) {
            throw ApiException.badRequest("Vai tro khong hop le");
        }
    }

    public void validatePassword(String password) {
        if (password == null || password.length() < 10
                || !password.matches(".*[A-Z].*")
                || !password.matches(".*[a-z].*")
                || !password.matches(".*\\d.*")
                || !password.matches(".*[^A-Za-z0-9].*")) {
            throw ApiException.badRequest(
                    "Mat khau phai co it nhat 10 ky tu, chu hoa, chu thuong, so va ky tu dac biet");
        }
    }

    private String temporaryPassword() {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "Sse@" + token.substring(0, 1).toUpperCase(Locale.ROOT) + token.substring(1) + "7";
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
