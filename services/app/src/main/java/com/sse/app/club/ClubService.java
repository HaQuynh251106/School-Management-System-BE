package com.sse.app.club;

import com.sse.app.club.ClubDtos.ClubRegistrationView;
import com.sse.app.club.ClubDtos.ClubView;
import com.sse.app.club.ClubDtos.CreateClubRequest;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.finance.FinanceService;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import com.sse.app.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class ClubService {
    private static final Set<String> ACTIVE_REGISTRATION_STATUSES =
            Set.of("PENDING", "WAITLIST", "APPROVED");

    private final ClubRepository clubs;
    private final ClubRegistrationRepository registrations;
    private final UserService users;
    private final FinanceService finance;
    private final NotificationService notifications;

    public ClubService(ClubRepository clubs, ClubRegistrationRepository registrations,
                       UserService users, FinanceService finance, NotificationService notifications) {
        this.clubs = clubs;
        this.registrations = registrations;
        this.users = users;
        this.finance = finance;
        this.notifications = notifications;
    }

    public List<ClubView> list() {
        return clubs.findAllByOrderByNameAsc().stream().map(this::view).toList();
    }

    @Transactional
    public ClubView create(CreateClubRequest request) {
        String code = request.code().trim().toUpperCase();
        if (clubs.findByCodeIgnoreCase(code).isPresent()) {
            throw ApiException.conflict("Mã câu lạc bộ đã tồn tại");
        }
        if (request.registrationEnd().isBefore(request.registrationStart())) {
            throw ApiException.badRequest("Ngày kết thúc đăng ký phải từ ngày bắt đầu trở đi");
        }
        Club club = clubs.save(Club.builder()
                .id(request.id() == null || request.id().isBlank() ? Ids.gen("club") : request.id().trim())
                .code(code)
                .name(request.name().trim())
                .description(blankToNull(request.description()))
                .schedule(request.schedule().trim())
                .capacity(request.capacity())
                .feeAmount(request.feeAmount())
                .approvalRequired(request.approvalRequired())
                .registrationStart(request.registrationStart())
                .registrationEnd(request.registrationEnd())
                .active(!Boolean.FALSE.equals(request.active()))
                .createdAt(Instant.now())
                .build());
        return view(club);
    }

    @Transactional
    public ClubRegistrationView register(String clubId, String requestedStudentId, CurrentUser actor) {
        Club club = getClub(clubId);
        assertRegistrationWindow(club);
        String studentId;
        if (actor.isStudent()) {
            if (requestedStudentId != null && !requestedStudentId.isBlank()
                    && !actor.id().equals(requestedStudentId)) {
                throw ApiException.forbidden("Học sinh chỉ có thể đăng ký cho chính mình");
            }
            studentId = actor.id();
        } else if (actor.isParent()) {
            if (requestedStudentId == null || requestedStudentId.isBlank()) {
                throw ApiException.badRequest("Phụ huynh cần chọn học sinh đăng ký");
            }
            users.assertParentOf(actor.id(), requestedStudentId);
            studentId = requestedStudentId;
        } else {
            throw ApiException.forbidden("Chỉ học sinh hoặc phụ huynh được đăng ký câu lạc bộ");
        }
        User student = users.getById(studentId);
        if (!"STUDENT".equals(student.getRole()) || !"ACTIVE".equals(student.getStatus())) {
            throw ApiException.conflict("Học sinh không ở trạng thái có thể đăng ký");
        }

        ClubRegistration registration = registrations.findByClubIdAndStudentId(clubId, studentId)
                .orElse(null);
        if (registration != null && ACTIVE_REGISTRATION_STATUSES.contains(registration.getStatus())) {
            throw ApiException.conflict("Học sinh đã đăng ký câu lạc bộ này");
        }

        boolean full = approvedCount(clubId) >= club.getCapacity();
        String status = full ? "WAITLIST" : club.isApprovalRequired() ? "PENDING" : "APPROVED";
        if (registration == null) {
            registration = ClubRegistration.builder()
                    .id(Ids.gen("cr"))
                    .clubId(clubId)
                    .studentId(studentId)
                    .build();
        }
        registration.setRequestedBy(actor.id());
        registration.setStatus(status);
        registration.setInvoiceId(null);
        registration.setDecisionNote(null);
        registration.setCreatedAt(Instant.now());
        registration.setDecidedAt("APPROVED".equals(status) ? Instant.now() : null);
        registration.setCancelledAt(null);
        registration = registrations.save(registration);

        if ("APPROVED".equals(status)) issueInvoiceIfNeeded(registration, club);
        notifyOutcome(registration, club);
        return registrationView(registration);
    }

    public List<ClubRegistrationView> registrationsOfStudent(String studentId) {
        return registrations.findByStudentIdOrderByCreatedAtDesc(studentId).stream()
                .map(this::registrationView).toList();
    }

    public List<ClubRegistrationView> registrationsOfChild(String parentId, String studentId) {
        users.assertParentOf(parentId, studentId);
        return registrationsOfStudent(studentId);
    }

    public List<ClubRegistrationView> registrations(String clubId, String status) {
        return registrations.findAll().stream()
                .filter(item -> clubId == null || clubId.isBlank() || clubId.equals(item.getClubId()))
                .filter(item -> status == null || status.isBlank() || status.equalsIgnoreCase(item.getStatus()))
                .sorted(Comparator.comparing(ClubRegistration::getCreatedAt).reversed())
                .map(this::registrationView)
                .toList();
    }

    @Transactional
    public ClubRegistrationView approve(String id, String note) {
        ClubRegistration registration = getRegistration(id);
        if (!Set.of("PENDING", "WAITLIST").contains(registration.getStatus())) {
            throw ApiException.conflict("Chỉ có thể duyệt đăng ký đang chờ hoặc trong danh sách chờ");
        }
        Club club = getClub(registration.getClubId());
        if (approvedCount(club.getId()) >= club.getCapacity()) {
            registration.setStatus("WAITLIST");
            registrations.save(registration);
            throw ApiException.conflict("Câu lạc bộ đã đủ chỗ; đăng ký được giữ trong danh sách chờ");
        }
        registration.setStatus("APPROVED");
        registration.setDecisionNote(blankToNull(note));
        registration.setDecidedAt(Instant.now());
        registration = registrations.save(registration);
        issueInvoiceIfNeeded(registration, club);
        notifyOutcome(registration, club);
        return registrationView(registration);
    }

    @Transactional
    public ClubRegistrationView reject(String id, String note) {
        ClubRegistration registration = getRegistration(id);
        if (!Set.of("PENDING", "WAITLIST").contains(registration.getStatus())) {
            throw ApiException.conflict("Đăng ký không còn ở trạng thái có thể từ chối");
        }
        registration.setStatus("REJECTED");
        registration.setDecisionNote(blankToNull(note));
        registration.setDecidedAt(Instant.now());
        registration = registrations.save(registration);
        notifyOutcome(registration, getClub(registration.getClubId()));
        return registrationView(registration);
    }

    @Transactional
    public ClubRegistrationView cancel(String id, String reason, CurrentUser actor) {
        ClubRegistration registration = getRegistration(id);
        if (!ACTIVE_REGISTRATION_STATUSES.contains(registration.getStatus())) {
            throw ApiException.conflict("Đăng ký không còn ở trạng thái có thể hủy");
        }
        if (actor.isStudent() && !actor.id().equals(registration.getStudentId())) {
            throw ApiException.forbidden("Không thể hủy đăng ký của học sinh khác");
        }
        if (actor.isParent()) users.assertParentOf(actor.id(), registration.getStudentId());
        if (!actor.isAdmin() && !actor.isStudent() && !actor.isParent()) {
            throw ApiException.forbidden("Không có quyền hủy đăng ký này");
        }

        boolean releasedSeat = "APPROVED".equals(registration.getStatus());
        if (registration.getInvoiceId() != null) {
            finance.cancelOrRefundClubInvoice(registration.getInvoiceId(),
                    blankToNull(reason) == null ? "Hủy đăng ký câu lạc bộ" : reason.trim(), actor.id());
        }
        registration.setStatus("CANCELLED");
        registration.setDecisionNote(blankToNull(reason));
        registration.setCancelledAt(Instant.now());
        registration = registrations.save(registration);
        Club club = getClub(registration.getClubId());
        notifyOutcome(registration, club);
        if (releasedSeat) promoteWaitlist(club);
        return registrationView(registration);
    }

    private void promoteWaitlist(Club club) {
        if (approvedCount(club.getId()) >= club.getCapacity()) return;
        List<ClubRegistration> waiting = registrations
                .findByClubIdAndStatusOrderByCreatedAtAsc(club.getId(), "WAITLIST");
        if (waiting.isEmpty()) return;
        ClubRegistration promoted = waiting.get(0);
        promoted.setStatus("APPROVED");
        promoted.setDecisionNote("Tự động duyệt khi có chỗ trống");
        promoted.setDecidedAt(Instant.now());
        registrations.save(promoted);
        issueInvoiceIfNeeded(promoted, club);
        notifyOutcome(promoted, club);
    }

    private void issueInvoiceIfNeeded(ClubRegistration registration, Club club) {
        if (club.getFeeAmount() <= 0 || registration.getInvoiceId() != null) return;
        String invoiceId = finance.createClubInvoice(
                club.getId(), registration.getId(), club.getName(), registration.getStudentId(), club.getFeeAmount());
        registration.setInvoiceId(invoiceId);
        registrations.save(registration);
    }

    private void notifyOutcome(ClubRegistration registration, Club club) {
        String title = switch (registration.getStatus()) {
            case "APPROVED" -> "Đăng ký CLB đã được duyệt";
            case "WAITLIST" -> "Đăng ký CLB đang trong danh sách chờ";
            case "PENDING" -> "Đăng ký CLB đang chờ duyệt";
            case "REJECTED" -> "Đăng ký CLB bị từ chối";
            case "CANCELLED" -> "Đăng ký CLB đã hủy";
            default -> "Cập nhật đăng ký CLB";
        };
        String body = club.getName() + " - trạng thái: " + registration.getStatus();
        List<String> recipients = new ArrayList<>();
        recipients.add(registration.getStudentId());
        recipients.addAll(users.parentIdsOf(registration.getStudentId()));
        notifications.notifyUsers(recipients.stream().distinct().toList(), "CLUB", "IMPORTANT",
                title, body, "CLUB_REGISTRATION", registration.getId() + ":" + registration.getStatus());
    }

    private ClubView view(Club club) {
        int approved = approvedCount(club.getId());
        int waitlist = Math.toIntExact(registrations.countByClubIdAndStatus(club.getId(), "WAITLIST"));
        return new ClubView(club.getId(), club.getCode(), club.getName(), club.getDescription(),
                club.getSchedule(), club.getCapacity(), approved, Math.max(0, club.getCapacity() - approved),
                waitlist, club.getFeeAmount(), club.isApprovalRequired(), club.getRegistrationStart(),
                club.getRegistrationEnd(), club.isActive());
    }

    private ClubRegistrationView registrationView(ClubRegistration registration) {
        Club club = getClub(registration.getClubId());
        User student = users.getById(registration.getStudentId());
        int waitlistPosition = 0;
        if ("WAITLIST".equals(registration.getStatus())) {
            List<ClubRegistration> waiting = registrations
                    .findByClubIdAndStatusOrderByCreatedAtAsc(registration.getClubId(), "WAITLIST");
            for (int i = 0; i < waiting.size(); i++) {
                if (waiting.get(i).getId().equals(registration.getId())) {
                    waitlistPosition = i + 1;
                    break;
                }
            }
        }
        return new ClubRegistrationView(registration.getId(), club.getId(), club.getName(),
                student.getId(), student.getFullName(), registration.getRequestedBy(), registration.getStatus(),
                registration.getInvoiceId(), registration.getDecisionNote(), waitlistPosition,
                registration.getCreatedAt(), registration.getDecidedAt(), registration.getCancelledAt());
    }

    private Club getClub(String id) {
        return clubs.findById(id).orElseThrow(() -> ApiException.notFound("Câu lạc bộ"));
    }

    private ClubRegistration getRegistration(String id) {
        return registrations.findById(id).orElseThrow(() -> ApiException.notFound("Đăng ký câu lạc bộ"));
    }

    private int approvedCount(String clubId) {
        return Math.toIntExact(registrations.countByClubIdAndStatus(clubId, "APPROVED"));
    }

    private void assertRegistrationWindow(Club club) {
        if (!club.isActive()) throw ApiException.conflict("Câu lạc bộ đang tạm ngừng đăng ký");
        LocalDate today = LocalDate.now();
        if (today.isBefore(club.getRegistrationStart()) || today.isAfter(club.getRegistrationEnd())) {
            throw ApiException.conflict("Ngoài thời gian đăng ký câu lạc bộ");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
