package com.xetax.crm.whatsapp.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * One customer's completed Flow.
 *
 * <p>Kept even when the answers could not be turned into a CRM record, so a
 * submission is never lost to a mapping mistake — the raw answers stay here
 * and can be replayed once the mapping is fixed.
 */
@Entity
@Table(name = "whatsapp_flow_responses",
        indexes = {
                @Index(name = "idx_wa_flowresp_owner", columnList = "owner_user_id"),
                @Index(name = "idx_wa_flowresp_flow", columnList = "flow_id"),
                @Index(name = "idx_wa_flowresp_token", columnList = "flow_token")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppFlowResponse extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(name = "flow_id")
    private Long flowId;

    /** The token we minted when sending, which ties this reply to that send. */
    @Column(name = "flow_token", length = 120)
    private String flowToken;

    @Column(name = "customer_phone", length = 32)
    private String customerPhone;

    @Column(name = "conversation_id")
    private Long conversationId;

    /** Exactly what the customer submitted, as Meta delivered it. */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String answersJson;

    /** The CRM record these answers created or updated, when one was. */
    @Column(name = "record_id", length = 64)
    private String recordId;

    @Column(length = 500)
    private String note;
}
