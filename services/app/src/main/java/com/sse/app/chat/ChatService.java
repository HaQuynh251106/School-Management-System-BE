package com.sse.app.chat;

import com.sse.app.common.Ids;
import com.sse.app.common.PageResponse;
import com.sse.app.common.Paging;
import com.sse.app.identity.UserService;
import com.sse.app.identity.UserDto;
import com.sse.app.security.CurrentUser;
import com.sse.app.realtime.RealtimeEventHub;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TeachingAssignmentService;
import com.sse.app.common.ApiException;
import com.sse.app.file.FileStorageService;
import com.sse.app.file.StoredFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.*;

/** B6/D3: Chat 1-1, phân trang REST và cập nhật tức thời qua SSE. */
@Service
public class ChatService {

    public record ChatContactScope(String classId, String classCode, String relation) {}

    private final ChatRepository repo;
    private final UserService users;
    private final StructureService structure;
    private final TeachingAssignmentService teachingAssignments;
    private final FileStorageService storage;
    private final RealtimeEventHub realtime;

    public ChatService(ChatRepository repo, UserService users, StructureService structure,
                       TeachingAssignmentService teachingAssignments, FileStorageService storage,
                       RealtimeEventHub realtime) {
        this.repo = repo;
        this.users = users;
        this.structure = structure;
        this.teachingAssignments = teachingAssignments;
        this.storage = storage;
        this.realtime = realtime;
    }

    private List<ChatMessage> involving(String meId) {
        return repo.findBySenderIdOrRecipientIdOrderByCreatedAtAsc(meId, meId);
    }

    /** Tin nhắn giữa "tôi" và một người khác (theo thứ tự thời gian). */
    @Transactional
    public List<ChatMessage> conversation(String meId, String otherId) {
        publishReadReceiptAfterCommit(meId, otherId,
                repo.markConversationRead(meId, otherId, Instant.now()));
        List<ChatMessage> newest = repo.findConversation(meId, otherId,
                Paging.request(0, 200, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        List<ChatMessage> chronological = new ArrayList<>(newest);
        Collections.reverse(chronological);
        return chronological;
    }

    /** Page zero contains the newest messages; clients can prepend older pages when scrolling upward. */
    @Transactional
    public PageResponse<ChatMessage> conversationPage(String meId, String otherId, int page, int size) {
        int marked = repo.markConversationRead(meId, otherId, Instant.now());
        publishReadReceiptAfterCommit(meId, otherId, marked);
        return PageResponse.from(repo.findConversation(meId, otherId,
                Paging.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    /** Danh sách hội thoại: mỗi đối tác + tin nhắn cuối + số chưa đọc. */
    public List<Map<String, Object>> threads(CurrentUser current) {
        String meId = current.id();
        Set<String> allowedContactIds = contactIds(current);
        Map<String, ChatMessage> last = new LinkedHashMap<>();
        Map<String, Integer> unread = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (ChatMessage m : involving(meId)) {
            boolean iAmSender = meId.equals(m.getSenderId());
            String other = iAmSender ? m.getRecipientId() : m.getSenderId();
            if (!allowedContactIds.contains(other)) continue;
            String otherName = iAmSender ? m.getRecipientName() : m.getSenderName();
            names.put(other, otherName);
            last.put(other, m); // vì đã sort tăng dần → cuối cùng là mới nhất
            if (!iAmSender && !m.isReadFlag()) unread.merge(other, 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : last.entrySet()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("userId", e.getKey());
            t.put("name", names.get(e.getKey()));
            t.put("lastMessage", e.getValue().getBody() == null || e.getValue().getBody().isBlank()
                    ? "📎 " + e.getValue().getAttachmentName() : e.getValue().getBody());
            t.put("lastTime", e.getValue().getCreatedAt());
            t.put("unread", unread.getOrDefault(e.getKey(), 0));
            out.add(t);
        }
        out.sort((a, b) -> ((Instant) b.get("lastTime")).compareTo((Instant) a.get("lastTime")));
        return out;
    }

    /** Tổng số tin nhắn người dùng chưa đọc, dùng cho huy hiệu điều hướng toàn hệ thống. */
    public long unreadCount(CurrentUser current) {
        Set<String> allowedContactIds = contactIds(current);
        return involving(current.id()).stream()
                .filter(message -> current.id().equals(message.getRecipientId()))
                .filter(message -> !message.isReadFlag())
                .filter(message -> allowedContactIds.contains(message.getSenderId()))
                .count();
    }

    public ChatMessage send(String meId, String meName, String toId, String body, String attachmentFileId) {
        String normalizedBody = body == null ? "" : body.trim();
        StoredFile attachment = attachmentFileId == null || attachmentFileId.isBlank()
                ? null : storage.ownedMetadata(attachmentFileId, meId);
        if (normalizedBody.isEmpty() && attachment == null) throw ApiException.badRequest("Cần nhập nội dung hoặc đính kèm tệp");
        if (normalizedBody.length() > 2000) {
            throw ApiException.badRequest("Tin nhắn không được vượt quá 2.000 ký tự");
        }
        ChatMessage saved = repo.save(ChatMessage.builder()
                .id(Ids.gen("msg")).senderId(meId).senderName(meName)
                .recipientId(toId).recipientName(users.fullNameOf(toId))
                .body(normalizedBody.isEmpty() ? null : normalizedBody)
                .attachmentFileId(attachment == null ? null : attachment.getId())
                .attachmentName(attachment == null ? null : attachment.getOriginalName())
                .readFlag(false).createdAt(Instant.now()).build());
        realtime.publish(toId, "CHAT", Map.of(
                "messageId", saved.getId(), "fromUserId", meId, "toUserId", toId));
        realtime.publish(meId, "CHAT", Map.of(
                "messageId", saved.getId(), "fromUserId", meId, "toUserId", toId));
        return saved;
    }

    public boolean canAccessFile(String fileId, CurrentUser actor) {
        return repo.findByAttachmentFileId(fileId).map(message -> actor.isAdmin()
                || actor.id().equals(message.getSenderId()) || actor.id().equals(message.getRecipientId())).orElse(false);
    }

    private LinkedHashSet<String> contactIds(CurrentUser current) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (current.isAdmin()) {
            ids.addAll(users.allUserIds());
        } else if (current.isParent()) {
            users.childrenOf(current.id()).forEach(child -> {
                if (child.classId() != null) {
                    String homeroom = structure.getClass(child.classId()).getHomeroomTeacherId();
                    if (homeroom != null) ids.add(homeroom);
                    teachingAssignments.assignmentsOfClass(child.classId(), null)
                            .forEach(item -> ids.add(item.getTeacherId()));
                }
            });
        } else if (current.isStudent()) {
            var student = users.getById(current.id());
            if (student.getClassId() != null) {
                String homeroom = structure.getClass(student.getClassId()).getHomeroomTeacherId();
                if (homeroom != null) ids.add(homeroom);
                teachingAssignments.assignmentsOfClass(student.getClassId(), null)
                        .forEach(item -> ids.add(item.getTeacherId()));
                users.listSummaries("STUDENT", null, student.getClassId())
                        .forEach(classmate -> ids.add(classmate.id()));
            }
        } else if (current.isTeacher()) {
            for (var schoolClass : structure.classesOfHomeroom(current.id())) {
                String classId = schoolClass.getId();
                teachingAssignments.assignmentsOfClass(classId, null)
                        .forEach(item -> ids.add(item.getTeacherId()));
                for (UserDto student : users.listSummaries("STUDENT", null, classId)) {
                    ids.add(student.id());
                    ids.addAll(users.parentIdsOf(student.id()));
                }
            }
            teachingAssignments.assignmentsOfTeacher(current.id()).forEach(item -> {
                String classId = item.getClassId();
                String homeroom = structure.getClass(classId).getHomeroomTeacherId();
                if (homeroom != null) ids.add(homeroom);
                for (UserDto student : users.listSummaries("STUDENT", null, classId)) {
                    ids.add(student.id());
                    ids.addAll(users.parentIdsOf(student.id()));
                }
            });
        }
        ids.remove(current.id());
        return ids;
    }

    public List<UserDto> contacts(CurrentUser current) {
        LinkedHashSet<String> ids = contactIds(current);
        return ids.stream().map(users::getById).filter(user -> "ACTIVE".equals(user.getStatus()))
                .map(users::toSummaryDto).sorted(Comparator.comparing(UserDto::fullName)).toList();
    }

    /** Phạm vi lớp của từng liên hệ để client lọc danh bạ mà không làm lộ lớp ngoài nhiệm vụ hiện tại. */
    public Map<String, List<ChatContactScope>> contactScopes(CurrentUser current) {
        Set<String> visibleClassIds = new LinkedHashSet<>();
        if (current.isTeacher()) {
            structure.classesOfHomeroom(current.id()).forEach(item -> visibleClassIds.add(item.getId()));
            teachingAssignments.assignmentsOfTeacher(current.id())
                    .forEach(item -> visibleClassIds.add(item.getClassId()));
        } else if (current.isStudent()) {
            UserDto student = users.dtoById(current.id());
            if (student.classId() != null) visibleClassIds.add(student.classId());
        } else if (current.isParent()) {
            users.childrenOf(current.id()).stream().map(UserDto::classId).filter(Objects::nonNull)
                    .forEach(visibleClassIds::add);
        }

        Map<String, List<ChatContactScope>> result = new LinkedHashMap<>();
        for (String contactId : contactIds(current)) {
            UserDto contact = users.dtoById(contactId);
            LinkedHashMap<String, ChatContactScope> scopes = new LinkedHashMap<>();
            if ("STUDENT".equals(contact.role()) && contact.classId() != null) {
                addContactScope(scopes, contact.classId(), "Học sinh");
            } else if ("PARENT".equals(contact.role())) {
                users.childrenOf(contactId).stream().map(UserDto::classId).filter(Objects::nonNull)
                        .forEach(classId -> addContactScope(scopes, classId, "Phụ huynh"));
            } else if ("TEACHER".equals(contact.role())) {
                structure.classesOfHomeroom(contactId)
                        .forEach(schoolClass -> addContactScope(scopes, schoolClass.getId(), "Giáo viên chủ nhiệm"));
            }
            List<ChatContactScope> filtered = scopes.values().stream()
                    .filter(scope -> current.isAdmin() || visibleClassIds.contains(scope.classId()))
                    .toList();
            result.put(contactId, filtered);
        }
        return result;
    }

    private void addContactScope(Map<String, ChatContactScope> scopes, String classId, String relation) {
        var schoolClass = structure.getClass(classId);
        scopes.putIfAbsent(classId, new ChatContactScope(classId, schoolClass.getCode(), relation));
    }

    public void assertCanContact(CurrentUser current, String otherId) {
        if (otherId == null || otherId.isBlank() || current.id().equals(otherId)) {
            throw ApiException.badRequest("Người nhận không hợp lệ");
        }
        boolean allowed = contactIds(current).contains(otherId);
        if (!allowed) throw ApiException.forbidden("Bạn không thể nhắn tin với người dùng này theo phạm vi liên lạc được phân công");
    }

    private void publishReadReceiptAfterCommit(String readByUserId, String otherId, int marked) {
        if (marked <= 0) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                realtime.publish(otherId, "CHAT_READ", Map.of(
                        "readByUserId", readByUserId,
                        "withUserId", otherId));
            }
        });
    }

    public void seed(List<ChatMessage> list) {
        repo.saveAll(list);
    }
}
