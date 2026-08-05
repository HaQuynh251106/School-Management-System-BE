package com.sse.app.identity;

import com.sse.app.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RbacServiceTest {
    @Mock private RoleRepository roles;
    @Mock private PermissionRepository permissions;
    @Mock private RolePermissionRepository grants;
    @Mock private UserRoleRepository userRoles;

    private RbacService service;

    @BeforeEach
    void setUp() {
        service = new RbacService(roles, permissions, grants, userRoles);
    }

    @Test
    void adminPermissionsCannotBeChanged() {
        Role admin = Role.builder()
                .id("role-admin")
                .code("ADMIN")
                .name("Quản trị viên")
                .active(true)
                .build();
        when(roles.findById("role-admin")).thenReturn(Optional.of(admin));

        ApiException error = assertThrows(ApiException.class,
                () -> service.replacePermissions(
                        "role-admin", List.of("AUDIT_READ"), "admin-1"));

        assertEquals(400, error.getStatus().value());
        verifyNoInteractions(permissions, grants, userRoles);
    }
}
