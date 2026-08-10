package com.sse.app.chat;

import com.sse.app.common.ApiException;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import com.sse.app.identity.UserService;
import com.sse.app.identity.UserDto;
import jakarta.validation.constraints.NotBlank;
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

    public record SendMessageRequest(@NotBlank String toUserId, @NotBlank String body) {}

    @GetMapping("/threads")
    public List<Map<String, Object>> threads() {
        return chat.threads(CurrentUserHolder.require().id());
    }

    @GetMapping("/contacts")
    public List<UserDto> contacts() {
        return chat.contacts(CurrentUserHolder.require());
    }

    @GetMapping("/messages")
    public List<ChatMessage> messages(@RequestParam String withUserId) {
        return chat.conversation(CurrentUserHolder.require(), withUserId);
    }

    @PostMapping("/messages")
    public ChatMessage send(@RequestBody SendMessageRequest req) {
        CurrentUser me = CurrentUserHolder.require();
        if (req == null || req.toUserId() == null || req.body() == null)
            throw ApiException.badRequest("Thiếu người nhận hoặc nội dung");
        String meName = users.fullNameOf(me.id());
        return chat.send(me, meName, req.toUserId(), req.body());
    }
}
