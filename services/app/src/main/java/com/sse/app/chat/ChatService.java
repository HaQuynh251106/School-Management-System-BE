package com.sse.app.chat;

import com.sse.app.common.Ids;
import com.sse.app.identity.UserService;
import com.sse.app.identity.UserDto;
import com.sse.app.security.CurrentUser;
import com.sse.app.common.ApiException;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
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

    public ChatService(ChatRepository repo, UserService users,
                       StructureService structure,
                       TeachingAssignmentService teachingAssignments) {
        this.repo = repo;
        this.users = users;
        this.structure = structure;
        this.teachingAssignments = teachingAssignments;
    }

    private List<ChatMessage> involving(String meId) {
        return repo.findBySenderIdOrRecipientIdOrderByCreatedAtAsc(meId, meId);
    }

    /** Tin nhắn giữa "tôi" và một người khác (theo thứ tự thời gian). */
    @Transactional
    public List<ChatMessage> conversation(CurrentUser me, String otherId) {
        assertCanContact(me, otherId);
        List<ChatMessage> unread = repo.findBySenderIdAndRecipientIdAndReadFlagIsFalse(otherId, me.id());
        unread.forEach(message -> message.setReadFlag(true));
        repo.saveAll(unread);
        return involving(me.id()).stream()
                .filter(m -> (me.id().equals(m.getSenderId()) && otherId.equals(m.getRecipientId()))
                        || (otherId.equals(m.getSenderId()) && me.id().equals(m.getRecipientId())))
                .toList();
    }

    /** Danh sách hội thoại: mỗi đối tác + tin nhắn cuối + số chưa đọc. */
    public List<Map<String, Object>> threads(String meId) {
        Map<String, ChatMessage> last = new LinkedHashMap<>();
        Map<String, Integer> unread = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (ChatMessage m : involving(meId)) {
            boolean iAmSender = meId.equals(m.getSenderId());
            String other = iAmSender ? m.getRecipientId() : m.getSenderId();
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
            t.put("lastMessage", e.getValue().getBody());
            t.put("lastTime", e.getValue().getCreatedAt());
            t.put("unread", unread.getOrDefault(e.getKey(), 0));
            out.add(t);
        }
        out.sort((a, b) -> ((Instant) b.get("lastTime")).compareTo((Instant) a.get("lastTime")));
        return out;
    }

    public ChatMessage send(CurrentUser me, String meName, String toId, String body) {
        assertCanContact(me, toId);
        String normalized = body == null ? "" : body.trim();
        if (normalized.isBlank()) throw ApiException.badRequest("Nội dung tin nhắn không được để trống");
        if (normalized.length() > 2000) throw ApiException.badRequest("Tin nhắn không được vượt quá 2000 ký tự");
        return repo.save(ChatMessage.builder()
                .id(Ids.gen("msg")).senderId(me.id()).senderName(meName)
                .recipientId(toId).recipientName(users.fullNameOf(toId))
                .body(normalized).readFlag(false).createdAt(Instant.now()).build());
    }

    public List<UserDto> contacts(CurrentUser me) {
        LinkedHashSet<String> ids = contactIds(me);
        ids.remove(me.id());
        return ids.stream().map(users::dtoById)
                .sorted(Comparator.comparing(UserDto::fullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    private void assertCanContact(CurrentUser me, String otherId) {
        if (otherId == null || otherId.isBlank() || !contactIds(me).contains(otherId)) {
            throw ApiException.forbidden("Chỉ được nhắn tin trong phạm vi lớp và GVCN liên quan");
        }
    }

    private LinkedHashSet<String> contactIds(CurrentUser me) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (me.isAdmin()) {
            result.addAll(users.allUserIds());
            return result;
        }
        if (me.isParent()) {
            for (UserDto child : users.childrenOf(me.id())) {
                if (child.classId() == null) continue;
                SchoolClass schoolClass = structure.getClass(child.classId());
                if (schoolClass.getHomeroomTeacherId() != null) result.add(schoolClass.getHomeroomTeacherId());
            }
            return result;
        }
        if (me.isStudent()) {
            UserDto student = users.dtoById(me.id());
            if (student.classId() == null) return result;
            SchoolClass schoolClass = structure.getClass(student.classId());
            if (schoolClass.getHomeroomTeacherId() != null) result.add(schoolClass.getHomeroomTeacherId());
            teachingAssignments.list(null, student.classId(), null, null, "ACTIVE")
                    .forEach(assignment -> result.add(assignment.teacherId()));
            return result;
        }
        if (me.isTeacher()) {
            LinkedHashSet<String> teachingClassIds = teachingAssignments
                    .list(me.id(), null, null, null, "ACTIVE").stream()
                    .map(assignment -> assignment.classId())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            LinkedHashSet<String> homeroomClassIds = structure.classesOfHomeroom(me.id()).stream()
                    .map(SchoolClass::getId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            teachingClassIds.forEach(classId -> users.list("STUDENT", null, classId)
                    .forEach(student -> result.add(student.id())));
            homeroomClassIds.forEach(classId -> users.list("STUDENT", null, classId).forEach(student -> {
                    result.add(student.id());
                    result.addAll(users.parentIdsOf(student.id()));
                }));
        }
        return result;
    }

    public void seed(List<ChatMessage> list) {
        repo.saveAll(list);
    }
}
