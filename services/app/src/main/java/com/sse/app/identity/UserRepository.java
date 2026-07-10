package com.sse.app.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByStudentCodeIgnoreCase(String studentCode);
    Optional<User> findByRoleAndPhone(String role, String phone);
    Optional<User> findByRoleAndEmailIgnoreCase(String role, String email);
    List<User> findByRole(String role);
    List<User> findByClassId(String classId);
    boolean existsByUsername(String username);
    long countByRoleAndClassId(String role, String classId);

    @Query("""
            select u from User u
            where lower(u.username) = lower(:identifier)
               or lower(coalesce(u.email, '')) = lower(:identifier)
               or u.phone = :identifier
               or lower(coalesce(u.studentCode, '')) = lower(:identifier)
            """)
    List<User> findByLoginIdentifier(@Param("identifier") String identifier);
}
