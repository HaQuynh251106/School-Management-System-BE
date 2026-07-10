package com.sse.app.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ParentStudentRepository extends JpaRepository<ParentStudent, String> {
    List<ParentStudent> findByParentId(String parentId);
    List<ParentStudent> findByStudentId(String studentId);
    boolean existsByParentIdAndStudentId(String parentId, String studentId);
    boolean existsByStudentId(String studentId);
}
