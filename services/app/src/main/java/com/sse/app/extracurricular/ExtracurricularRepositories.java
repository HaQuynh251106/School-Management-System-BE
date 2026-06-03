package com.sse.app.extracurricular;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ClubRepository extends JpaRepository<Club, String> {
}

interface ClubRegistrationRepository extends JpaRepository<ClubRegistration, String> {
    List<ClubRegistration> findByClubId(String clubId);
    List<ClubRegistration> findByStudentId(String studentId);
    Optional<ClubRegistration> findByClubIdAndStudentId(String clubId, String studentId);
    long countByClubIdAndStatus(String clubId, String status);
}
