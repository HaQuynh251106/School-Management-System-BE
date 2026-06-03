package com.sse.app.extracurricular;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.extracurricular.ExtracurricularDtos.*;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** A5/C6/D5: Quản lý CLB ngoại khóa + đăng ký. */
@Service
public class ExtracurricularService {

    private final ClubRepository clubs;
    private final ClubRegistrationRepository registrations;
    private final UserService users;
    private final NotificationService notifications;

    public ExtracurricularService(ClubRepository clubs, ClubRegistrationRepository registrations,
                                  UserService users, NotificationService notifications) {
        this.clubs = clubs;
        this.registrations = registrations;
        this.users = users;
        this.notifications = notifications;
    }

    public List<Club> listClubs() { return clubs.findAll(); }

    public Club createClub(CreateClubRequest r) {
        return clubs.save(Club.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("club") : r.id())
                .name(r.name()).description(r.description())
                .capacity(r.capacity() == null ? 0 : r.capacity())
                .schedule(r.schedule()).fee(r.fee() == null ? 0 : r.fee())
                .status("OPEN").createdAt(Instant.now()).build());
    }

    public Club getClub(String id) {
        return clubs.findById(id).orElseThrow(() -> ApiException.notFound("CLB"));
    }

    @Transactional
    public ClubRegistration register(String clubId, String studentId, String by) {
        Club club = getClub(clubId);
        if (!"OPEN".equals(club.getStatus())) throw ApiException.badRequest("CLB đã đóng đăng ký");

        ClubRegistration existing = registrations.findByClubIdAndStudentId(clubId, studentId).orElse(null);
        if (existing != null && "REGISTERED".equals(existing.getStatus())) {
            throw ApiException.conflict("Học sinh đã đăng ký CLB này");
        }
        if (club.getCapacity() > 0
                && registrations.countByClubIdAndStatus(clubId, "REGISTERED") >= club.getCapacity()) {
            throw ApiException.conflict("CLB đã đủ sĩ số");
        }

        ClubRegistration reg = existing != null ? existing
                : ClubRegistration.builder().id(Ids.gen("creg")).build();
        reg.setClubId(clubId);
        reg.setClubName(club.getName());
        reg.setStudentId(studentId);
        reg.setStudentName(users.fullNameOf(studentId));
        reg.setRegisteredBy(by);
        reg.setStatus("REGISTERED");
        reg.setRegisteredAt(Instant.now());
        registrations.save(reg);

        notifications.notifyUser(studentId, "ANNOUNCEMENT", "Đăng ký ngoại khóa thành công",
                "Bạn đã đăng ký CLB " + club.getName(), "CLUB", clubId);
        return reg;
    }

    @Transactional
    public ClubRegistration cancel(String regId) {
        ClubRegistration reg = registrations.findById(regId)
                .orElseThrow(() -> ApiException.notFound("Đăng ký"));
        reg.setStatus("CANCELLED");
        return registrations.save(reg);
    }

    public List<ClubRegistration> registrationsOf(String clubId) { return registrations.findByClubId(clubId); }

    public List<ClubRegistration> registrationsByStudent(String studentId) {
        return registrations.findByStudentId(studentId);
    }
}
