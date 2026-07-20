package com.sse.app.chat;

import com.sse.app.common.Ids;
import com.sse.app.identity.UserService;
import com.sse.app.identity.UserDto;
import com.sse.app.security.CurrentUser;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TeachingAssignmentService;
import com.sse.app.common.ApiException;
import com.sse.app.file.FileStorageService;
import com.sse.app.file.StoredFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** B6/D3: Chat 1-1 (polling). */
@Service
public class ChatService {

    private final ChatRepository repo;
    private final UserService users;
    private final StructureService structure;
    private final TeachingAssignmentService teachingAssignments;
    private final FileStorageService storage;

    public ChatService(ChatRepository repo, UserService users, StructureService structure,
                       TeachingAssignmentService teachingAssignments, FileStorageService storage) {
        this.repo = repo;
        this.users = users;
        this.structure = structure;
        this.teachingAssignments = teachingAssignments;
        this.storage = storage;
    }

    private List<ChatMessage> involving(String meId) {
        return repo.findBySenderIdOrRecipientIdOrderByCreatedAtAsc(meId, meId);
    }

    /** Tin nhắn giữa "tôi" và một người khác (theo thứ tự thời gian). */
    @Transactional
    public List<ChatMessage> conversation(String meId, String otherId) {
        repo.markConversationRead(meId, otherId, Instant.now());
        return involving(meId).stream()
                .filter(m -> (meId.equals(m.getSenderId()) && otherId.equals(m.getRecipientId()))
                        || (otherId.equals(m.getSenderId()) && meId.equals(m.getRecipientId())))
                .toList();
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

    public ChatMessage send(String meId, String meName, String toId, String body, String attachmentFileId) {
        String normalizedBody = body == null ? "" : body.trim();
        StoredFile attachment = attachmentFileId == null || attachmentFileId.isBlank()
                ? null : storage.ownedMetadata(attachmentFileId, meId);
        if (normalizedBody.isEmpty() && attachment == null) throw ApiException.badRequest("Cần nhập nội dung hoặc đính kèm tệp");
        if (normalizedBody.length() > 2000) {
            throw ApiException.badRequest("Tin nhắn không được vượt quá 2.000 ký tự");
        }
        return repo.save(ChatMessage.builder()
                .id(Ids.gen("msg")).senderId(meId).senderName(meName)
                .recipientId(toId).recipientName(users.fullNameOf(toId))
                .body(normalizedBody.isEmpty() ? null : normalizedBody)
                .attachmentFileId(attachment == null ? null : attachment.getId())
                .attachmentName(attachment == null ? null : attachment.getOriginalName())
                .readFlag(false).createdAt(Instant.now()).build());
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
                String homeroom = structure.getClass(item.getClassId()).getHomeroomTeacherId();
                if (homeroom != null) ids.add(homeroom);
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

    public void assertCanContact(CurrentUser current, String otherId) {
        if (otherId == null || otherId.isBlank() || current.id().equals(otherId)) {
            throw ApiException.badRequest("Người nhận không hợp lệ");
        }
        boolean allowed = contactIds(current).contains(otherId);
        if (!allowed) throw ApiException.forbidden("Bạn không thể nhắn tin với người dùng này theo phạm vi liên lạc được phân công");
    }

    public void seed(List<ChatMessage> list) {
        repo.saveAll(list);
    }
}
