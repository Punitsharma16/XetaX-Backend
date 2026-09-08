package com.xetax.crm.agent.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * An admin-built public chatbot. publicKey goes into the embed script and is
 * the ONLY thing a visitor's browser ever holds — it can chat with this
 * agent's knowledge and nothing else. CRM data/tools are never reachable
 * from the public path.
 */
@Entity
@Table(name = "ai_agents",
        uniqueConstraints = @UniqueConstraint(name = "uq_agent_key", columnNames = "public_key"),
        indexes = @Index(name = "idx_agent_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiAgent extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "public_key", nullable = false, length = 40)
    private String publicKey;

    /** Persona/instructions the admin writes ("tum ek polite sales assistant ho…"). */
    @Column(length = 2000)
    private String persona;

    @Column(length = 500)
    private String welcomeMessage;

    @Column(length = 16)
    private String themeColor;

    /** ACTIVE | DISABLED */
    @Column(nullable = false, length = 10)
    private String status;
}
