package com.sse.app.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByStudentCodeIgnoreCase(String studentCode);
    Optional<User> findByTeacherCodeIgnoreCase(String teacherCode);
    List<User> findByRole(String role);
    List<User> findByClassId(String classId);
    long countByClassIdAndRole(String classId, String role);
    boolean existsByUsername(String username);
    boolean existsByStudentCodeIgnoreCase(String studentCode);
}
