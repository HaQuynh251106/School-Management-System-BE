package com.sse.app.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ChatRepository extends JpaRepository<ChatMessage, String> {
    List<ChatMessage> findBySenderIdOrRecipientIdOrderByCreatedAtAsc(String senderId, String recipientId);
    List<ChatMessage> findBySenderIdAndRecipientIdAndReadFlagIsFalse(String senderId, String recipientId);
}
