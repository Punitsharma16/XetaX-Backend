package com.xetax.crm.document;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One uploaded document (quotation, brochure, price list…). Bytes live on
 * disk under APP_UPLOAD_DIR; this row is the metadata. DOCX files support
 * {{field_key}} variables that are filled from a record before sending.
 */
@Entity
@Table(name = "documents", indexes = @Index(name = "idx_doc_owner", columnList = "ownerUserId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String ownerUserId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(length = 120)
    private String contentType;

    private Long size;

    @Column(nullable = false, length = 400)
    private String storagePath;

    /** True for .docx — {{variables}} can be replaced per record. */
    private boolean supportsVariables;

    private LocalDateTime createdAt;
}
