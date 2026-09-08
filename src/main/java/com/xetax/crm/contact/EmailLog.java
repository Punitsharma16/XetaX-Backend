package com.xetax.crm.contact;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One outgoing email, written by OrgSmtpService.sendAs — every path (contact
 * page, meeting invites, SEND_EMAIL automations) funnels through there, so a
 * contact's history is simply "logs to their address".
 */
@Entity
@Table(name = "email_logs", indexes = {
        @Index(name = "idx_email_log_owner", columnList = "ownerUserId"),
        @Index(name = "idx_email_log_to", columnList = "ownerUserId, toEmail")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String ownerUserId;

    @Column(nullable = false, length = 160)
    private String toEmail;

    @Column(length = 300)
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String body;

    /** SENT | FAILED */
    @Column(length = 12)
    private String status;

    @Column(length = 500)
    private String error;

    private LocalDateTime createdAt;
}
