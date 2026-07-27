package com.sse.app.chat;

import com.sse.app.common.PageResponse;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import com.sse.app.identity.UserService;
import com.sse.app.identity.UserDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** B6/D3: Chat 1-1. */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chat;
    private final UserService users;

    public ChatController(ChatService chat, UserService users) {
        this.chat = chat;
        this.users = users;
    }

    public record SendMessageRequest(
            @NotBlank(message = "Thiếu người nhận") String toUserId,
            @Size(max = 2000, message = "Tin nhắn không được vượt quá 2.000 ký tự") String body,
            String attachmentFileId
    ) {}

    @GetMapping("/threads")
    public List<Map<String, Object>> threads() {
        return chat.threads(CurrentUserHolder.require());
    }

    @GetMapping("/contacts")
    public List<UserDto> contacts() {
        return chat.contacts(CurrentUserHolder.require());
    }

    @GetMapping("/contact-scopes")
    public Map<String, List<ChatService.ChatContactScope>> contactScopes() {
        return chat.contactScopes(CurrentUserHolder.require());
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", chat.unreadCount(CurrentUserHolder.require().id()));
    }

    @GetMapping("/messages")
    public List<ChatMessage> messages(@RequestParam String withUserId) {
        CurrentUser current = CurrentUserHolder.require();
        chat.assertCanContact(current, withUserId);
        return chat.conversation(current.id(), withUserId);
    }

    @GetMapping("/messages/page")
    public PageResponse<ChatMessage> messagePage(@RequestParam String withUserId,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        CurrentUser current = CurrentUserHolder.require();
        chat.assertCanContact(current, withUserId);
        return chat.conversationPage(current.id(), withUserId, page, size);
    }

    @PostMapping("/messages")
    public ChatMessage send(@Valid @RequestBody SendMessageRequest req) {
        CurrentUser me = CurrentUserHolder.require();
        chat.assertCanContact(me, req.toUserId());
        String meName = users.fullNameOf(me.id());
        return chat.send(me.id(), meName, req.toUserId(), req.body(), req.attachmentFileId());
    }
}
