package com.sse.app.identity;

import com.sse.app.audit.AuditService;
import com.sse.app.identity.RbacDtos.*;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/admin/rbac")
public class RbacController {
    private final RbacService rbac;
    private final AuditService audit;

    public RbacController(RbacService rbac, AuditService audit) {
        this.rbac = rbac;
        this.audit = audit;
    }

    @GetMapping("/roles")
    public List<RoleResponse> roles() {
        CurrentUserHolder.requirePermission("IDENTITY_RBAC_MANAGE");
        return rbac.roles();
    }

    @GetMapping("/permissions")
    public List<PermissionResponse> permissions() {
        CurrentUserHolder.requirePermission("IDENTITY_RBAC_MANAGE");
        return rbac.permissions();
    }

    @PutMapping("/roles/{roleId}/permissions")
    public RoleResponse replacePermissions(
            @PathVariable String roleId,
            @Valid @RequestBody ReplaceRolePermissionsRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_RBAC_MANAGE");
        RoleResponse result = rbac.replacePermissions(roleId, request.permissionCodes(), actor.id());
        audit.record(actor.id(), actor.username(), actor.role(), "RBAC_UPDATE",
                "identity", "role", roleId,
                "Permissions=" + String.join(",", result.permissionCodes())
                        + "; reason=" + Optional.ofNullable(request.reason()).orElse(""));
        return result;
    }
}
