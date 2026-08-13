package com.sse.app.identity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BusinessCodeCounterRepository
        extends JpaRepository<BusinessCodeCounter, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select counter from BusinessCodeCounter counter where counter.codeType = :codeType")
    Optional<BusinessCodeCounter> findForUpdate(@Param("codeType") String codeType);
}
