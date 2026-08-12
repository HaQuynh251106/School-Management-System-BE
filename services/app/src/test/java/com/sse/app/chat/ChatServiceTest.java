package com.sse.app.chat;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.common.ApiException;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {
    @Mock ChatRepository repo;
    @Mock UserService users;
    @Mock StructureService structure;
    @Mock TeachingAssignmentService teachingAssignments;
    @Mock ChatRealtimeService realtime;
    ChatService service;

    @BeforeEach
    void setUp() { service = new ChatService(repo, users, structure, teachingAssignments, realtime); }

    @Test
    void parentCanOnlyContactChildHomeroomTeacher() {
        CurrentUser parent = new CurrentUser("parent-1", "ph1", "PARENT");
        when(users.childrenOf("parent-1")).thenReturn(List.of(user("student-1", "class-1")));
        when(structure.getClass("class-1")).thenReturn(SchoolClass.builder()
                .id("class-1").homeroomTeacherId("teacher-1").build());
        when(users.fullNameOf("teacher-1")).thenReturn("Cô chủ nhiệm");
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ChatMessage sent = service.send(parent, "Phụ huynh", "teacher-1", "  Xin chào cô  ");

        assertEquals("Xin chào cô", sent.getBody());
        verify(realtime).publishMessage(sent);
        assertThrows(ApiException.class,
                () -> service.send(parent, "Phụ huynh", "teacher-2", "Sai phạm vi"));
    }

    @Test
    void openingConversationMarksReceivedMessagesRead() {
        CurrentUser parent = new CurrentUser("parent-1", "ph1", "PARENT");
        when(users.childrenOf("parent-1")).thenReturn(List.of(user("student-1", "class-1")));
        when(structure.getClass("class-1")).thenReturn(SchoolClass.builder()
                .id("class-1").homeroomTeacherId("teacher-1").build());
        ChatMessage unread = ChatMessage.builder().id("msg-1").senderId("teacher-1")
                .recipientId("parent-1").body("Thông báo").readFlag(false).build();
        when(repo.findBySenderIdAndRecipientIdAndReadFlagIsFalse("teacher-1", "parent-1"))
                .thenReturn(List.of(unread));
        when(repo.findBySenderIdOrRecipientIdOrderByCreatedAtAsc("parent-1", "parent-1"))
                .thenReturn(List.of(unread));

        service.conversation(parent, "teacher-1");

        assertTrue(unread.isReadFlag());
        verify(repo).saveAll(List.of(unread));
        verify(realtime).publishRead("parent-1", "teacher-1", List.of("msg-1"));
    }

    private UserDto user(String id, String classId) {
        return new UserDto(id, id, id, "STUDENT", "ACTIVE", null, null, null,
                id, "10A1", classId, null, null, List.of());
    }
}
