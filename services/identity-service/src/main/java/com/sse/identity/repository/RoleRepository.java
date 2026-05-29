package com.sse.identity.repository;

import com.sse.identity.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository — tầng truy cập DB.
 *
 * Chỉ cần `extends JpaRepository<Entity, KiểuPK>` là Spring TỰ ĐỘNG sinh ra:
 *   - findAll(), findById(id), save(e), deleteById(id), count()...
 *
 * Muốn thêm query custom → đặt tên method theo convention "findBy<Field>" hoặc
 * dùng @Query("...JPQL..."). Spring tự sinh implementation, KHÔNG cần viết tay.
 */
@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(String code);

    boolean existsByCode(String code);
}
