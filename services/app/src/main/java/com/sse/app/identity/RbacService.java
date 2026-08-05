package com.sse.app.identity;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.RbacDtos.PermissionResponse;
import com.sse.app.identity.RbacDtos.RoleResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class RbacService {
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final RolePermissionRepository grants;
    private final UserRoleRepository userRoles;

    public RbacService(RoleRepository roles, PermissionRepository permissions,
                       RolePermissionRepository grants, UserRoleRepository userRoles) {
        this.roles = roles;
        this.permissions = permissions;
        this.grants = grants;
        this.userRoles = userRoles;
    }

    public Set<String> permissionsFor(String userId) {
        return Set.copyOf(permissions.findActiveCodesByUserId(userId));
    }

    public List<PermissionResponse> permissions() {
        return permissions.findByActiveTrueOrderByModuleAscCodeAsc().stream()
                .map(p -> new PermissionResponse(
                        p.getId(), p.getCode(), p.getModule(), p.getName(), p.getDescription()))
                .toList();
    }

    public List<RoleResponse> roles() {
        return roles.findByActiveTrueOrderByCode().stream().map(this::toResponse).toList();
    }

    @Transactional
    public RoleResponse replacePermissions(String roleId, List<String> requestedCodes,
                                           String actorId) {
        Role role = roles.findById(roleId).orElseThrow(() -> ApiException.notFound("Vai tro"));
        if ("ADMIN".equals(role.getCode())) {
            throw ApiException.badRequest(
                    "Quản trị viên luôn có toàn quyền và không thể sửa phân quyền");
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>(
                requestedCodes == null ? List.of() : requestedCodes);
        List<Permission> selected = codes.isEmpty()
                ? List.of()
                : permissions.findByCodeIn(new ArrayList<>(codes));
        Set<String> found = selected.stream().map(Permission::getCode).collect(
                java.util.stream.Collectors.toSet());
        List<String> unknown = codes.stream().filter(code -> !found.contains(code)).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("Quyen khong ton tai: " + String.join(", ", unknown));
        }

        grants.deleteByRoleId(roleId);
        grants.flush();
        Instant now = Instant.now();
        grants.saveAll(selected.stream().map(permission -> RolePermission.builder()
                .id(Ids.gen("rp"))
                .roleId(roleId)
                .permissionId(permission.getId())
                .grantedAt(now)
                .grantedBy(actorId)
                .build()).toList());
        return toResponse(role);
    }

    @Transactional
    public void assignPrimaryRole(String userId, String roleCode, String actorId) {
        Role role = roles.findByCode(roleCode)
                .orElseThrow(() -> ApiException.badRequest("Vai tro khong hop le"));
        userRoles.deleteAll(userRoles.findByUserId(userId));
        userRoles.flush();
        userRoles.save(UserRole.builder()
                .id(Ids.gen("ur"))
                .userId(userId)
                .roleId(role.getId())
                .assignedAt(Instant.now())
                .assignedBy(actorId)
                .build());
    }

    private RoleResponse toResponse(Role role) {
        Map<String, Permission> byId = permissions.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Permission::getId, p -> p));
        List<String> codes = grants.findByRoleId(role.getId()).stream()
                .map(RolePermission::getPermissionId)
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(Permission::getCode)
                .sorted()
                .toList();
        return new RoleResponse(role.getId(), role.getCode(), role.getName(),
                role.getDescription(), role.isSystemRole(), role.isActive(), codes);
    }
}
