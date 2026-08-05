package com.sse.app.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermission, String> {
    List<RolePermission> findByRoleId(String roleId);
    void deleteByRoleId(String roleId);
}
