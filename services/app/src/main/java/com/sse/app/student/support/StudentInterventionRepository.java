package com.sse.app.student.support;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface StudentInterventionRepository extends
        JpaRepository<StudentIntervention, String>, JpaSpecificationExecutor<StudentIntervention> {
}
