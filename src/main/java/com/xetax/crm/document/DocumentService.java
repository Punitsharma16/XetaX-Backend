package com.xetax.crm.document;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.contact.Contact;
import com.xetax.crm.contact.ContactRepository;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormEntity;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.data_manager.repository.FormRepo;
import com.xetax.crm.data_manager.repository.RecordRepo;
import com.xetax.crm.data_manager.service.FormMetaCache;
import com.xetax.crm.settings.service.OrgSmtpService;
import com.xetax.crm.team.service.PermissionService;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Document library + sending. Files live on disk (APP_UPLOAD_DIR), metadata
 * in MySQL. DOCX documents support {{field_key}} variables filled from a
 * RECORD's data (contacts deliberately get no personalization). Sends reuse
 * the org's channels: WhatsApp media (24h window applies) and org SMTP email
 * with attachment.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private static final long MAX_SIZE = 5L * 1024 * 1024;
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final DocumentFileRepository repository;
    private final DocumentPersonalizer personalizer;
    private final CurrentUserProvider currentUserProvider;
    private final PermissionService permissionService;
    private final OrgSmtpService orgSmtpService;
    private final WhatsAppMessagingService whatsAppMessagingService;
    private final RecordRepo recordRepo;
    private final FormRepo formRepo;
    private final FormMetaCache formMetaCache;
    private final ContactRepository contactRepository;
    private final com.xetax.crm.activity.RecordActivityService activityService;

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    private String owner() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new BadRequestException("Not signed in");
        return id.toString();
    }

    // ------------------------------------------------------------ crud

    public DocumentFile upload(MultipartFile file, String name) throws IOException {
        if (file == null || file.isEmpty()) throw new BadRequestException("Choose a file to upload");
        if (file.getSize() > MAX_SIZE) throw new BadRequestException("File is too large — 5 MB maximum");
        String original = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String extension = original.contains(".")
                ? original.substring(original.lastIndexOf('.')).toLowerCase() : "";
        boolean docx = extension.equals(".docx") || DOCX_MIME.equals(file.getContentType());

        Path dir = Path.of(uploadDir, owner());
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID().toString().replace("-", "") + extension);
        file.transferTo(target.toAbsolutePath());

        return repository.save(DocumentFile.builder()
                .ownerUserId(owner())
                .name(name == null || name.isBlank() ? stripExtension(original) : name.trim())
                .originalFilename(original)
                .contentType(file.getContentType())
                .size(file.getSize())
                .storagePath(target.toAbsolutePath().toString())
                .supportsVariables(docx)
                .createdAt(LocalDateTime.now())
                .build());
    }

    public List<DocumentFile> list() {
        return repository.findByOwnerUserIdOrderByIdDesc(owner());
    }

    public DocumentFile get(Long id) {
        return repository.findByIdAndOwnerUserId(id, owner())
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
    }

    public void delete(Long id) {
        DocumentFile document = get(id);
        try {
            Files.deleteIfExists(Path.of(document.getStoragePath()));
        } catch (IOException e) {
            log.warn("Could not delete file {}: {}", document.getStoragePath(), e.getMessage());
        }
        repository.delete(document);
    }

    public byte[] bytesOf(DocumentFile document) {
        try {
            return Files.readAllBytes(Path.of(document.getStoragePath()));
        } catch (IOException e) {
            throw new BadRequestException("The stored file is missing on the server");
        }
    }

    /** Personalized copy for a record — download/preview and every record send use this. */
    public byte[] personalizedBytes(DocumentFile document, String recordId) {
        byte[] bytes = bytesOf(document);
        if (recordId == null || recordId.isBlank() || !document.isSupportsVariables()) {
            return bytes;
        }
        RecordDocument record = ownedRecord(recordId);
        Map<String, Object> data = record.getData() == null ? Map.of() : record.getData();
        return personalizer.personalize(bytes, data);
    }

    // ------------------------------------------------------------ sending

    public record SendRequest(String channel, String phone, String to, String subject,
                              String message, String recordId, Long contactId,
                              Boolean personalize) {}

    public void send(Long documentId, SendRequest request) {
        permissionService.requireAny("contacts.manage", "records.edit");
        DocumentFile document = get(documentId);

        String phone = request.phone();
        String to = request.to();
        if (request.contactId() != null) {
            Contact contact = contactRepository.findByIdAndOwnerUserId(request.contactId(), owner())
                    .orElseThrow(() -> new ResourceNotFoundException("Contact not found"));
            if (phone == null || phone.isBlank()) phone = contact.getPhone();
            if (to == null || to.isBlank()) to = contact.getEmail();
        }

        // Personalization is a RECORD feature only — a contact send always
        // gets the document exactly as uploaded.
        boolean personalize = request.recordId() != null && !request.recordId().isBlank()
                && !Boolean.FALSE.equals(request.personalize());
        byte[] bytes = personalize
                ? personalizedBytes(document, request.recordId())
                : bytesOf(document);

        if ("WHATSAPP".equalsIgnoreCase(request.channel())) {
            if (phone == null || phone.isBlank()) throw new BadRequestException("No phone number to send to");
            sendWhatsApp(document, phone, bytes, request.message());
        } else {
            if (to == null || to.isBlank()) throw new BadRequestException("No email address to send to");
            String subject = request.subject() == null || request.subject().isBlank()
                    ? document.getName() : request.subject();
            sendEmail(document, to, subject,
                    request.message() == null ? "" : request.message(), bytes);
        }

        // Record timeline — only when this send belongs to a record.
        if (request.recordId() != null && !request.recordId().isBlank()) {
            activityService.log(request.recordId(), owner(), "DOCUMENT_SENT",
                    "Document '" + document.getName() + "' sent via "
                            + request.channel().toUpperCase()
                            + (personalize ? " (personalized)" : ""));
        }
    }

    public record BulkSendRequest(List<String> recordIds, String channel, String subject,
                                  String message, Boolean personalize) {}

    /** Bulk send over records — each lead gets its own personalized copy. */
    public Map<String, Object> sendBulk(Long documentId, BulkSendRequest request) {
        permissionService.requireAny("contacts.manage", "records.edit");
        DocumentFile document = get(documentId);
        if (request.recordIds() == null || request.recordIds().isEmpty()) {
            throw new BadRequestException("Select at least one record");
        }
        boolean personalize = !Boolean.FALSE.equals(request.personalize());
        boolean whatsapp = "WHATSAPP".equalsIgnoreCase(request.channel());

        int sent = 0;
        List<String> failed = new ArrayList<>();
        for (String recordId : request.recordIds()) {
            String label = recordId;
            try {
                RecordDocument record = ownedRecord(recordId);
                Map<String, Object> data = record.getData() == null ? Map.of() : record.getData();
                label = firstText(data, recordId);
                byte[] bytes = personalize && document.isSupportsVariables()
                        ? personalizer.personalize(bytesOf(document), data)
                        : bytesOf(document);
                if (whatsapp) {
                    String phone = contactValue(record, true);
                    if (phone == null) { failed.add(label + " (no phone)"); continue; }
                    sendWhatsApp(document, phone, bytes, request.message());
                } else {
                    String to = contactValue(record, false);
                    if (to == null) { failed.add(label + " (no email)"); continue; }
                    String subject = request.subject() == null || request.subject().isBlank()
                            ? document.getName() : request.subject();
                    sendEmail(document, to, subject,
                            request.message() == null ? "" : request.message(), bytes);
                }
                sent++;
                activityService.log(recordId, owner(), "DOCUMENT_SENT",
                        "Document '" + document.getName() + "' sent via "
                                + (whatsapp ? "WHATSAPP" : "EMAIL") + " (bulk)");
            } catch (Exception e) {
                failed.add(label);
                log.warn("Bulk document send to record {} failed: {}", recordId, e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", sent);
        out.put("failed", failed.size());
        out.put("failedNames", failed);
        return out;
    }

    // ------------------------------------------------------------ helpers

    private void sendWhatsApp(DocumentFile document, String phone, byte[] bytes, String caption) {
        try {
            whatsAppMessagingService.sendDocumentAsOwner(owner(), phone, bytes,
                    document.getOriginalFilename(), document.getContentType(), caption);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    private void sendEmail(DocumentFile document, String to, String subject, String body, byte[] bytes) {
        orgSmtpService.sendWithAttachment(owner(), to, subject, body,
                document.getOriginalFilename(), bytes,
                document.getContentType() == null ? "application/octet-stream" : document.getContentType());
    }

    private RecordDocument ownedRecord(String recordId) {
        RecordDocument record = recordRepo.findById(recordId)
                .orElseThrow(() -> new ResourceNotFoundException("Record not found"));
        FormEntity form = formRepo.findById(record.getFormId())
                .orElseThrow(() -> new ResourceNotFoundException("Form not found"));
        if (!owner().equals(form.getOwnerUserId())) {
            throw new ResourceNotFoundException("Record not found");
        }
        return record;
    }

    /** First PHONE-ish (or EMAIL-ish) field value of the record. */
    private String contactValue(RecordDocument record, boolean phone) {
        Map<String, Object> data = record.getData() == null ? Map.of() : record.getData();
        for (FormField field : formMetaCache.getFields(record.getFormId())) {
            String type = field.getFieldType() == null ? "" : field.getFieldType().name();
            String key = field.getFieldKey() == null ? "" : field.getFieldKey().toLowerCase();
            boolean match = phone
                    ? type.equals("PHONE") || key.contains("phone") || key.contains("mobile") || key.contains("whatsapp")
                    : type.equals("EMAIL") || key.contains("email");
            if (match) {
                Object value = data.get(field.getFieldKey());
                if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
            }
        }
        return null;
    }

    private String firstText(Map<String, Object> data, String fallback) {
        return data.values().stream()
                .filter(v -> v instanceof String s && !s.isBlank())
                .map(Object::toString).findFirst().orElse(fallback);
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
