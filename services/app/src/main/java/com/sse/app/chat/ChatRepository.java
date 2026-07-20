package com.sse.app.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

interface ChatRepository extends JpaRepository<ChatMessage, String> {
    List<ChatMessage> findBySenderIdOrRecipientIdOrderByCreatedAtAsc(String senderId, String recipientId);
    Optional<ChatMessage> findByAttachmentFileId(String attachmentFileId);

    @Modifying
    @Query("""
            update ChatMessage message
               set message.readFlag = true,
                   message.readAt = :readAt
             where message.senderId = :otherId
               and message.recipientId = :meId
               and message.readFlag = false
            """)
    int markConversationRead(@Param("meId") String meId, @Param("otherId") String otherId,
                             @Param("readAt") Instant readAt);
}
