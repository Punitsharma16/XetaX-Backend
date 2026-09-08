package com.xetax.crm.data_manager.entity;

import com.xetax.crm.data_manager.enums.FormStatus;
import jakarta.persistence.*;
import lombok.*;


@Entity
@Table(name="forms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormEntity extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    private String description;

    private String icon;

    /*
     * FormRequest and FormResponse both carry `color`, but the entity had no
     * column for it — the value was accepted on create and silently dropped, so
     * every form came back with color = null.
     */
    private String color;

    @Enumerated(EnumType.STRING)
    private FormStatus status;

    /*
     * Owning user's UUID (AuthUserEntity.id), stamped from the SecurityContext
     * on create. BaseEntity.createdBy is a Long and auth ids are UUIDs, so the
     * owner gets its own string column. getAll() filters on this.
     */
    @Column(name = "owner_user_id", length = 36)
    private String ownerUserId;

    /** Public lead-capture key — /f/{key} page and webhook use it. Null until shared. */
    @Column(unique = true, length = 64)
    private String publicKey;

    /** Master switch for the public form + webhook. */
    private Boolean publicEnabled;
}
