package com.sse.app.chat;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** B6/D3: Tin nhắn 1-1 giữa hai người dùng. */
@Entity
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_chat_sender", columnList = "senderId"),
        @Index(name = "idx_chat_recipient", columnList = "recipientId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatMessage {
    @Id
    private String id;
    private String senderId;
    private String senderName;
    private String recipientId;
    private String recipientName;
    @Column(length = 2000)
    private String body;
    private boolean readFlag;
    private Instant createdAt;
}
