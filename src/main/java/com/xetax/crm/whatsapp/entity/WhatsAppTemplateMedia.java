package com.xetax.crm.whatsapp.entity;

import com.xetax.crm.data_manager.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * A file uploaded as a template's media header or carousel card picture.
 *
 * <p>Meta keeps only a review sample; every later send must point at a public
 * link to the real file. This row is that file, stored on our server, so a
 * workspace never has to find its own hosting to send a picture template.
 */
@Entity
@Table(name = "whatsapp_template_media",
        uniqueConstraints = @UniqueConstraint(name = "uq_wa_tpl_media_key", columnNames = {"media_key"}),
        indexes = @Index(name = "idx_wa_tpl_media_owner", columnList = "owner_user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppTemplateMedia extends BaseEntity {

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    /** Unguessable; the only thing in the public link. */
    @Column(name = "media_key", length = 64, nullable = false)
    private String mediaKey;

    @Column(name = "storage_path", length = 512, nullable = false)
    private String storagePath;

    @Column(name = "mime_type", length = 64, nullable = false)
    private String mimeType;

    @Column(name = "original_name", length = 255)
    private String originalName;

    private Long size;
}
