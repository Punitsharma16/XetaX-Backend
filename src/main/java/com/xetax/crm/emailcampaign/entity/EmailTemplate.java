package com.xetax.crm.emailcampaign.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A reusable subject + body for email campaigns, saved by the workspace.
 *
 * <p>Nothing here is sent to anyone: a template only pre-fills the campaign
 * wizard. A campaign copies the text at creation time rather than pointing at
 * the template, so editing a template never rewrites a campaign that has
 * already gone out.
 */
@Entity
@Table(name = "email_templates",
        indexes = @Index(name = "idx_email_tpl_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailTemplate extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 500)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;
}
