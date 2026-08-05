package com.sse.app.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, String> {
    Optional<Permission> findByCode(String code);
    List<Permission> findByActiveTrueOrderByModuleAscCodeAsc();
    List<Permission> findByCodeIn(List<String> codes);

    @Query(value = """
            select distinct p.code
            from permissions p
            where p.active = true
              and (
                exists (
                  select 1
                  from user_roles admin_ur
                  join roles admin_role on admin_role.id = admin_ur.role_id
                  where admin_ur.user_id = :userId
                    and admin_role.code = 'ADMIN'
                    and admin_role.active = true
                )
                or exists (
                  select 1
                  from role_permissions rp
                  join user_roles ur on ur.role_id = rp.role_id
                  join roles r on r.id = ur.role_id
                  where ur.user_id = :userId
                    and rp.permission_id = p.id
                    and r.active = true
                )
              )
            order by p.code
            """, nativeQuery = true)
    List<String> findActiveCodesByUserId(@Param("userId") String userId);
}
