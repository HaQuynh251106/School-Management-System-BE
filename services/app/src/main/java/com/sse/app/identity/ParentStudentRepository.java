package com.sse.app.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ParentStudentRepository extends JpaRepository<ParentStudent, String> {
    List<ParentStudent> findByParentId(String parentId);
    List<ParentStudent> findByStudentId(String studentId);
    boolean existsByParentIdAndStudentId(String parentId, String studentId);
    Optional<ParentStudent> findByParentIdAndStudentId(String parentId, String studentId);
}
