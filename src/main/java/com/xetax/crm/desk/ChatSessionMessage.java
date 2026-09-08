package com.xetax.crm.desk;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** One line of a bot conversation. Role: CUSTOMER | AI | HUMAN | SYSTEM. */
@Entity
@Table(name = "bot_chat_messages",
        indexes = @Index(name = "idx_bot_msg_session", columnList = "sessionId,id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    /** Human sender's user id (role HUMAN) — for "Rahul joined" style labels. */
    @Column(length = 64)
    private String senderId;

    private LocalDateTime createdAt;
}
