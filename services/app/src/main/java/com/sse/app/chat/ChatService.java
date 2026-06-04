package com.sse.app.chat;

import com.sse.app.common.Ids;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/** B6/D3: Chat 1-1 (polling). */
@Service
public class ChatService {

    private final ChatRepository repo;
    private final UserService users;

    public ChatService(ChatRepository repo, UserService users) {
        this.repo = repo;
        this.users = users;
    }

    private List<ChatMessage> involving(String meId) {
        return repo.findBySenderIdOrRecipientIdOrderByCreatedAtAsc(meId, meId);
    }

    /** Tin nhắn giữa "tôi" và một người khác (theo thứ tự thời gian). */
    public List<ChatMessage> conversation(String meId, String otherId) {
        return involving(meId).stream()
                .filter(m -> (meId.equals(m.getSenderId()) && otherId.equals(m.getRecipientId()))
                        || (otherId.equals(m.getSenderId()) && meId.equals(m.getRecipientId())))
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

    public ChatMessage send(String meId, String meName, String toId, String body) {
        return repo.save(ChatMessage.builder()
                .id(Ids.gen("msg")).senderId(meId).senderName(meName)
                .recipientId(toId).recipientName(users.fullNameOf(toId))
                .body(body).readFlag(false).createdAt(Instant.now()).build());
    }

    public void seed(List<ChatMessage> list) {
        repo.saveAll(list);
    }
}
