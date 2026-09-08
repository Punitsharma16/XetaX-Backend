package com.xetax.crm.agent.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/** One knowledge source of an agent: a PDF, a web page, or pasted text. */
@Entity
@Table(name = "agent_sources",
        indexes = @Index(name = "idx_asrc_agent", columnList = "agent_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentSource extends BaseEntity {

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    /** PDF | URL | TEXT */
    @Column(nullable = false, length = 10)
    private String type;

    @Column(nullable = false, length = 300)
    private String name;

    /** INDEXED | FAILED */
    @Column(nullable = false, length = 10)
    private String status;

    @Column(nullable = false)
    private int chunkCount;

    @Column(nullable = false)
    private int contentChars;

    @Column(length = 500)
    private String error;
}
