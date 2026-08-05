package com.sse.app.identity;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class RbacDtos {
    private RbacDtos() {}

    public record PermissionResponse(
            String id, String code, String module, String name, String description) {}

    public record RoleResponse(
            String id, String code, String name, String description,
            boolean systemRole, boolean active, List<String> permissionCodes) {}

    public record ReplaceRolePermissionsRequest(
            @NotNull List<String> permissionCodes,
            @NotBlank @Size(min = 5, max = 500) String reason) {}
}
