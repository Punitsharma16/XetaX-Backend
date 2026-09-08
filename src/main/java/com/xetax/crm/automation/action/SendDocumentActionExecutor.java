package com.xetax.crm.automation.action;

import com.xetax.crm.automation.entity.AutomationAction;
import com.xetax.crm.automation.enums.AutomationActionType;
import com.xetax.crm.common.util.PlaceholderResolver;
import com.xetax.crm.data_manager.documents.RecordDocument;
import com.xetax.crm.data_manager.entity.FormField;
import com.xetax.crm.document.DocumentFile;
import com.xetax.crm.document.DocumentFileRepository;
import com.xetax.crm.document.DocumentPersonalizer;
import com.xetax.crm.settings.service.OrgSmtpService;
import com.xetax.crm.whatsapp.service.WhatsAppMessagingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * SEND_DOCUMENT: personalizes a stored document with the record's data
 * ({{fieldKey}} placeholders, same replacement every manual document send
 * uses) and delivers it on WhatsApp or email — so "stage/status badla →
 * agreement bhej do" needs no human.
 *
 * <p>Deliberately repository-level (not DocumentService): automations also run
 * with NO signed-in user (public form submits, webhooks), and the service's
 * owner() would throw there. Ownership is structural instead — the document
 * must belong to the form's owner, which the automation editor validated at
 * save time and this executor re-checks on every run.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SendDocumentActionExecutor implements ActionExecutor {

    private final DocumentFileRepository documentRepository;
    private final DocumentPersonalizer personalizer;
    private final OrgSmtpService orgSmtpService;
    private final WhatsAppMessagingService whatsAppMessagingService;
    private final com.xetax.crm.activity.RecordActivityService activityService;

    @Override
    public AutomationActionType getActionType() {
        return AutomationActionType.SEND_DOCUMENT;
    }

    @Override
    public boolean mutatesRecord() {
        return false;
    }

    @Override
    public void execute(AutomationAction action, RecordDocument record) {
        String owner = action.getAutomation().getForm().getOwnerUserId();

        if (action.getDocumentId() == null) {
            throw new IllegalArgumentException("SEND_DOCUMENT has no document configured.");
        }
        DocumentFile document = documentRepository
                .findByIdAndOwnerUserId(action.getDocumentId(), owner)
                .orElseThrow(() -> new IllegalArgumentException(
                        "The document this automation sends no longer exists."));

        FormField recipientField = action.getFormField();
        if (recipientField == null) {
            throw new IllegalArgumentException("SEND_DOCUMENT has no recipient field configured.");
        }
        Object raw = record.getData() == null ? null : record.getData().get(recipientField.getFieldKey());
        String recipient = raw == null ? "" : raw.toString().trim();
        if (recipient.isEmpty()) {
            throw new IllegalArgumentException(
                    "Record has no value in field '" + recipientField.getFieldKey() + "'.");
        }

        byte[] bytes = bytesOf(document);
        Map<String, Object> data = record.getData() == null ? Map.of() : record.getData();
        if (document.isSupportsVariables()) {
            bytes = personalizer.personalize(bytes, data);
        }

        String subject = PlaceholderResolver.resolve(
                orDefault(action.getEmailSubject(), document.getName()), data);
        String message = PlaceholderResolver.resolve(
                orDefault(action.getEmailMessage(), ""), data);

        boolean email = "EMAIL".equalsIgnoreCase(action.getChannel());
        if (email) {
            orgSmtpService.sendWithAttachment(owner, recipient, subject, message,
                    document.getOriginalFilename(), bytes, document.getContentType());
        } else {
            whatsAppMessagingService.sendDocumentAsOwner(owner, recipient, bytes,
                    document.getOriginalFilename(), document.getContentType(), message);
        }

        activityService.log(record.getId(), owner, "DOCUMENT_SENT",
                "Document '" + document.getName() + "' sent via "
                        + (email ? "EMAIL" : "WHATSAPP") + " (automation)");
    }

    private byte[] bytesOf(DocumentFile document) {
        try {
            return Files.readAllBytes(Path.of(document.getStoragePath()));
        } catch (Exception e) {
            throw new IllegalStateException("The stored file is missing on the server");
        }
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
