package com.sse.app.club;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ClubRepository extends JpaRepository<Club, String> {
    Optional<Club> findByCodeIgnoreCase(String code);
    List<Club> findAllByOrderByNameAsc();
}

interface ClubRegistrationRepository extends JpaRepository<ClubRegistration, String> {
    Optional<ClubRegistration> findByClubIdAndStudentId(String clubId, String studentId);
    List<ClubRegistration> findByStudentIdOrderByCreatedAtDesc(String studentId);
    List<ClubRegistration> findByClubIdOrderByCreatedAtAsc(String clubId);
    List<ClubRegistration> findByClubIdAndStatusOrderByCreatedAtAsc(String clubId, String status);
    long countByClubIdAndStatus(String clubId, String status);
}
